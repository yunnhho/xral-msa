package com.xrail.payment.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.payment.dto.PaymentRequest;
import com.xrail.payment.dto.PaymentResponse;
import com.xrail.payment.entity.Payment;
import com.xrail.payment.entity.enums.PaymentStatus;
import com.xrail.payment.pg.PaymentGateway;
import com.xrail.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private RedissonClient redissonClient;
    @Mock private OutboxRecorder outboxRecorder;
    @Mock private MeterRegistry meterRegistry;
    @Mock private PlatformTransactionManager txManager;
    @Mock private CouponService couponService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        Counter counter = mock(Counter.class);
        lenient().when(meterRegistry.counter(anyString())).thenReturn(counter);

        paymentService = new PaymentService(paymentRepository, paymentGateway,
                redissonClient, outboxRecorder, meterRegistry, txManager, couponService);
    }

    @Test
    void lockNotAcquired_throwsIdempotencyConflict() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 60, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.pay(1L,
                new PaymentRequest(100L, 50_000L, "CARD", null), "idem-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedIdempotencyKey_returnsExistingPayment() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // Transaction template must call the action
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        // Idempotency bucket says COMPLETED
        RBucket<Map<String, Object>> idemBucket = mock(RBucket.class);
        Map<String, Object> completedState = new HashMap<>();
        completedState.put("status", "COMPLETED");
        completedState.put("paymentId", 10L);
        when(redissonClient.getBucket(startsWith("payment:idem:"))).thenReturn((RBucket) idemBucket);
        when(idemBucket.get()).thenReturn(completedState);

        Payment existing = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(0L).build();
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.pay(1L,
                new PaymentRequest(100L, 50_000L, "CARD", null), "idem-key");

        assertThat(response.status()).isEqualTo("REQUESTED"); // Payment not completed in test
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPayment_pgSuccess_completesPayment() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        RBucket<Map<String, Object>> idemBucket = mock(RBucket.class);
        when(redissonClient.getBucket(startsWith("payment:idem:"))).thenReturn((RBucket) idemBucket);
        when(idemBucket.get()).thenReturn(null); // new

        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        Payment savedPayment = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(0L).build();
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(paymentGateway.charge(any(), anyLong(), anyString()))
                .thenReturn(new PaymentGateway.PgResult(true, "TXN-123", null));

        paymentService.pay(1L, new PaymentRequest(100L, 50_000L, "CARD", null), "idem-key");

        // P4: 이벤트는 직접 발행이 아니라 outbox에 기록된다 (payment.requested + payment.completed)
        verify(outboxRecorder, atLeastOnce()).record(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPayment_pgFailure_failsPayment() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        RBucket<Map<String, Object>> idemBucket = mock(RBucket.class);
        when(redissonClient.getBucket(startsWith("payment:idem:"))).thenReturn((RBucket) idemBucket);
        when(idemBucket.get()).thenReturn(null);

        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        Payment savedPayment = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(0L).build();
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(paymentGateway.charge(any(), anyLong(), anyString()))
                .thenReturn(new PaymentGateway.PgResult(false, null, "INSUFFICIENT_FUNDS"));

        PaymentResponse response = paymentService.pay(1L,
                new PaymentRequest(100L, 50_000L, "CARD", null), "idem-key");

        // PG failure → payment.fail() called → FAILED status
        assertThat(response.status()).isEqualTo("FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withCoupon_discountApplied() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        RBucket<Map<String, Object>> idemBucket = mock(RBucket.class);
        when(redissonClient.getBucket(startsWith("payment:idem:"))).thenReturn((RBucket) idemBucket);
        when(idemBucket.get()).thenReturn(null);

        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(couponService.applyDiscount("XRAIL10", 50_000L)).thenReturn(45_000L); // 5000 discount

        Payment savedPayment = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(5_000L).build();
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(paymentGateway.charge(any(), eq(45_000L), anyString()))
                .thenReturn(new PaymentGateway.PgResult(true, "TXN-123", null));

        paymentService.pay(1L, new PaymentRequest(100L, 50_000L, "CARD", "XRAIL10"), "idem-key");

        // PG charged with discounted amount
        verify(paymentGateway).charge(any(), eq(45_000L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidCoupon_rejectedBeforeCharge() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        RBucket<Map<String, Object>> idemBucket = mock(RBucket.class);
        when(redissonClient.getBucket(startsWith("payment:idem:"))).thenReturn((RBucket) idemBucket);
        when(idemBucket.get()).thenReturn(null);

        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(couponService.applyDiscount("BADCODE", 50_000L))
                .thenThrow(new BusinessException(ErrorCode.COUPON_INVALID));

        assertThatThrownBy(() -> paymentService.pay(1L,
                new PaymentRequest(100L, 50_000L, "CARD", "BADCODE"), "idem-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COUPON_INVALID));
        // 결제 INSERT/PG 호출 전에 거부된다
        verify(paymentRepository, org.mockito.Mockito.never()).save(any(Payment.class));
        verify(paymentGateway, org.mockito.Mockito.never()).charge(any(), anyLong(), anyString());
    }

    // ===== 환불 사가 =====

    @Test
    void refund_completedPayment_cancelsAndPublishes() {
        Payment payment = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(5_000L).build();
        payment.complete("TXN-1"); // COMPLETED 상태로 만든다
        when(paymentRepository.findFirstByReservationIdAndStatus(100L, PaymentStatus.COMPLETED))
                .thenReturn(Optional.of(payment));
        when(paymentGateway.refund(any(), eq(45_000L)))
                .thenReturn(new PaymentGateway.PgResult(true, "RF-1", null));

        paymentService.refund(100L, "USER_CANCEL");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentGateway).refund(any(), eq(45_000L)); // 청구액(할인 후) 환불
        verify(outboxRecorder).record(anyString(), anyString(), eq("payment.refunded"), anyString(), any());
    }

    @Test
    void refund_noCompletedPayment_isNoOp() {
        when(paymentRepository.findFirstByReservationIdAndStatus(100L, PaymentStatus.COMPLETED))
                .thenReturn(Optional.empty());

        paymentService.refund(100L, "USER_CANCEL");

        verify(paymentGateway, org.mockito.Mockito.never()).refund(any(), anyLong());
        verify(outboxRecorder, org.mockito.Mockito.never()).record(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void refund_pgFailure_throwsForDltRetry() {
        Payment payment = Payment.builder()
                .reservationId(100L).userId(1L).amount(50_000L)
                .method("CARD").idempotencyKey("idem-key").discountAmount(0L).build();
        payment.complete("TXN-1");
        when(paymentRepository.findFirstByReservationIdAndStatus(100L, PaymentStatus.COMPLETED))
                .thenReturn(Optional.of(payment));
        when(paymentGateway.refund(any(), anyLong()))
                .thenReturn(new PaymentGateway.PgResult(false, null, "REFUND_DECLINED"));

        assertThatThrownBy(() -> paymentService.refund(100L, "USER_CANCEL"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_FAILED));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED); // 환불 실패 → 상태 유지
        verify(outboxRecorder, org.mockito.Mockito.never()).record(anyString(), anyString(), anyString(), anyString(), any());
    }
}
