package com.xrail.payment.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.PaymentCompletedEvent;
import com.xrail.common.kafka.event.PaymentFailedEvent;
import com.xrail.common.kafka.event.PaymentRequestedEvent;
import com.xrail.payment.dto.PaymentRequest;
import com.xrail.payment.dto.PaymentResponse;
import com.xrail.payment.entity.Payment;
import com.xrail.payment.entity.enums.PaymentStatus;
import com.xrail.payment.pg.PaymentGateway;
import com.xrail.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PaymentService {

    private static final String IDEM_KEY_PREFIX = "payment:idem:";
    private static final String LOCK_KEY_PREFIX = "payment:lock:";

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway paymentGateway,
                          RedissonClient redissonClient, KafkaTemplate<String, Object> kafkaTemplate,
                          MeterRegistry meterRegistry, PlatformTransactionManager txManager) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.redissonClient = redissonClient;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * P1: lock 획득 → 트랜잭션 시작(txTemplate) → 처리 → 트랜잭션 커밋 → lock 해제.
     * lock이 @Transactional 외부에 있어 DB 트랜잭션 범위를 벗어나지 않는다.
     */
    public PaymentResponse pay(Long userId, PaymentRequest request, String idempotencyKey) {
        String lockKey = LOCK_KEY_PREFIX + idempotencyKey;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(0, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        if (!acquired) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }

        try {
            PaymentResponse result = txTemplate.execute(status -> processPayment(userId, request, idempotencyKey));
            if (result == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            return result;
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private PaymentResponse processPayment(Long userId, PaymentRequest request, String idempotencyKey) {
        String idemKey = IDEM_KEY_PREFIX + idempotencyKey;
        RBucket<Map<String, Object>> idemBucket = redissonClient.getBucket(idemKey);
        Map<String, Object> existing = idemBucket.get();

        // 이미 COMPLETED → 기존 응답 반환
        if (existing != null && "COMPLETED".equals(existing.get("status"))) {
            Long paymentId = ((Number) existing.get("paymentId")).longValue();
            return paymentRepository.findById(paymentId)
                    .map(PaymentResponse::of)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }

        // DB에서 중복 키 확인
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(found -> {
                    if (found.getStatus() == PaymentStatus.COMPLETED) {
                        return PaymentResponse.of(found);
                    }
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                })
                .orElseGet(() -> doCharge(userId, request, idempotencyKey, idemKey, idemBucket));
    }

    private PaymentResponse doCharge(Long userId, PaymentRequest request, String idempotencyKey,
                                      String idemKey, RBucket<Map<String, Object>> idemBucket) {
        // PROCESSING 상태로 마킹
        Map<String, Object> processingState = new HashMap<>();
        processingState.put("status", "PROCESSING");
        idemBucket.set(processingState, 30, TimeUnit.MINUTES);

        // INSERT Payment(REQUESTED)
        Payment payment = paymentRepository.save(Payment.builder()
                .reservationId(request.reservationId())
                .userId(userId)
                .amount(request.amount())
                .method(request.method())
                .idempotencyKey(idempotencyKey)
                .build());

        // payment.requested emit (감사용, P4)
        publishPaymentRequested(payment, idempotencyKey);

        // Mock PG 호출
        PaymentGateway.PgResult pgResult = paymentGateway.charge(payment.getId(), request.amount(), request.method());

        if (pgResult.success()) {
            payment.complete(pgResult.providerTxnId());
            meterRegistry.counter("xrail.payment.completed.total").increment();
            // P4 TODO: Transactional Outbox 패턴 도입 시 여기서 outbox 저장
            publishPaymentCompleted(payment);
            log.info("Payment completed paymentId={} reservationId={}", payment.getId(), payment.getReservationId());
        } else {
            payment.fail(pgResult.failureReason());
            meterRegistry.counter("xrail.payment.failed.total").increment();
            publishPaymentFailed(payment, pgResult.failureReason());
            log.info("Payment failed paymentId={} reason={}", payment.getId(), pgResult.failureReason());
        }

        // Redis 상태 갱신
        Map<String, Object> finalState = new HashMap<>();
        finalState.put("status", payment.getStatus().name());
        finalState.put("paymentId", payment.getId());
        idemBucket.set(finalState, 30, TimeUnit.MINUTES);

        return PaymentResponse.of(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getById(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!payment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return PaymentResponse.of(payment);
    }

    // ===== Kafka publishers =====

    private void publishPaymentRequested(Payment payment, String idempotencyKey) {
        PaymentRequestedEvent event = new PaymentRequestedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getMethod(),
                idempotencyKey
        );
        kafkaTemplate.send(Topics.PAYMENT_REQUESTED, String.valueOf(payment.getReservationId()), event);
    }

    private void publishPaymentCompleted(Payment payment) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getProviderTxnId()
        );
        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, String.valueOf(payment.getReservationId()), event);
    }

    private void publishPaymentFailed(Payment payment, String reason) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                payment.getAmount(),
                reason
        );
        kafkaTemplate.send(Topics.PAYMENT_FAILED, String.valueOf(payment.getReservationId()), event);
    }
}
