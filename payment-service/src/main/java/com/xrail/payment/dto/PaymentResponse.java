package com.xrail.payment.dto;

import com.xrail.payment.entity.Payment;

import java.time.ZoneId;

public record PaymentResponse(
        Long paymentId,
        Long reservationId,
        String status,
        Long amount,
        Long discountAmount,
        Long finalAmount,
        String couponCode,
        String method,
        String providerTxnId,
        String failureReason,
        String completedAt
) {
    public static PaymentResponse of(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getReservationId(),
                p.getStatus().name(),
                p.getAmount(),
                p.getDiscountAmount(),
                p.getAmount() - p.getDiscountAmount(),
                p.getCouponCode(),
                p.getMethod(),
                p.getProviderTxnId(),
                p.getFailureReason(),
                // LocalDateTime은 JVM 로컬 벽시계 — UTC로 거짓 태깅하면 KST 환경에서 +9h 표시된다.
                p.getUpdatedAt() != null ? p.getUpdatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString() : null
        );
    }
}
