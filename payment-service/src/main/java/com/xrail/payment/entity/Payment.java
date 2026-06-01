package com.xrail.payment.entity;

import com.xrail.common.entity.BaseTimeEntity;
import com.xrail.payment.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "payments")
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "provider_txn_id", length = 100)
    private String providerTxnId;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "discount_amount", nullable = false)
    private Long discountAmount = 0L;

    // P2: 낙관적 락
    @Version
    private Long version;

    protected Payment() {}

    @Builder
    public Payment(Long reservationId, Long userId, Long amount, String method, String idempotencyKey, String couponCode, Long discountAmount) {
        this.reservationId = reservationId;
        this.userId = userId;
        this.amount = amount;
        this.method = method;
        this.idempotencyKey = idempotencyKey;
        this.couponCode = couponCode;
        this.discountAmount = discountAmount != null ? discountAmount : 0L;
        this.status = PaymentStatus.REQUESTED;
    }

    public void complete(String providerTxnId) {
        this.status = PaymentStatus.COMPLETED;
        this.providerTxnId = providerTxnId;
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }
}
