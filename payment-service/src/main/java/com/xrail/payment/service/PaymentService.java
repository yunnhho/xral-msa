package com.xrail.payment.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.PaymentCompletedEvent;
import com.xrail.common.kafka.event.PaymentFailedEvent;
import com.xrail.common.kafka.event.PaymentRefundedEvent;
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
    private static final String OUTBOX_AGGREGATE = "Payment";

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final RedissonClient redissonClient;
    private final OutboxRecorder outboxRecorder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;
    private final CouponService couponService;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway paymentGateway,
                          RedissonClient redissonClient, OutboxRecorder outboxRecorder,
                          MeterRegistry meterRegistry, PlatformTransactionManager txManager,
                          CouponService couponService) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.redissonClient = redissonClient;
        this.outboxRecorder = outboxRecorder;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(txManager);
        this.couponService = couponService;
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

        // 쿠폰 할인 적용
        long discountAmount = 0L;
        String couponCode = request.couponCode();
        if (couponCode != null && !couponCode.isBlank()) {
            long finalAmount = couponService.applyDiscount(couponCode, request.amount());
            discountAmount = request.amount() - finalAmount;
        }
        long chargeAmount = request.amount() - discountAmount;

        // INSERT Payment(REQUESTED)
        Payment payment = paymentRepository.save(Payment.builder()
                .reservationId(request.reservationId())
                .userId(userId)
                .amount(request.amount())
                .method(request.method())
                .idempotencyKey(idempotencyKey)
                .couponCode(couponCode != null && !couponCode.isBlank() ? couponCode : null)
                .discountAmount(discountAmount)
                .build());

        // payment.requested emit (감사용, P4)
        publishPaymentRequested(payment, idempotencyKey);

        // Mock PG 호출 (쿠폰 적용 후 실제 청구 금액)
        PaymentGateway.PgResult pgResult = paymentGateway.charge(payment.getId(), chargeAmount, request.method());

        if (pgResult.success()) {
            payment.complete(pgResult.providerTxnId());
            meterRegistry.counter("xrail.payment.completed.total").increment();
            // P4: outbox에 기록 — 이 트랜잭션 커밋 후 relay가 Kafka로 발행 (이벤트 유실 0)
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

    /**
     * 환불 사가: train-service의 payment.refund-requested 수신 시 호출.
     * 멱등: 이미 CANCELLED이거나 COMPLETED 결제가 없으면 no-op. 환불 PG 실패는 throw → DLT 재시도.
     * 동일 reservationId 이벤트는 같은 파티션에서 순차 처리되므로 동시 이중환불은 발생하지 않는다.
     */
    @Transactional
    public void refund(Long reservationId, String reason) {
        Payment payment = paymentRepository
                .findFirstByReservationIdAndStatus(reservationId, PaymentStatus.COMPLETED)
                .orElse(null);
        if (payment == null) {
            // 이미 환불됨(CANCELLED) 또는 결제된 적 없음 → 멱등 no-op
            log.info("환불 대상 COMPLETED 결제 없음 — 멱등 처리 reservationId={}", reservationId);
            return;
        }

        long refundAmount = payment.getAmount() - payment.getDiscountAmount();
        PaymentGateway.PgResult pgResult = paymentGateway.refund(payment.getId(), refundAmount);
        if (!pgResult.success()) {
            // PG 환불 실패 → 예외로 Kafka 재시도/DLT 격리
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }

        payment.cancel();
        meterRegistry.counter("xrail.payment.refunded.total").increment();
        publishPaymentRefunded(payment, refundAmount);
        log.info("Payment refunded paymentId={} reservationId={} amount={} reason={}",
                payment.getId(), reservationId, refundAmount, reason);
    }

    // ===== Kafka publishers (P4: outbox 경유) =====

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
        record(Topics.PAYMENT_REQUESTED, payment.getReservationId(), event);
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
        record(Topics.PAYMENT_COMPLETED, payment.getReservationId(), event);
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
        record(Topics.PAYMENT_FAILED, payment.getReservationId(), event);
    }

    private void publishPaymentRefunded(Payment payment, long refundAmount) {
        PaymentRefundedEvent event = new PaymentRefundedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                payment.getId(),
                payment.getReservationId(),
                payment.getUserId(),
                refundAmount
        );
        record(Topics.PAYMENT_REFUNDED, payment.getReservationId(), event);
    }

    private void record(String topic, Long reservationId, Object event) {
        outboxRecorder.record(OUTBOX_AGGREGATE, String.valueOf(reservationId), topic,
                String.valueOf(reservationId), event);
    }
}
