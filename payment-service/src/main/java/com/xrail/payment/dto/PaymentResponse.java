package com.xrail.payment.dto;

import com.xrail.payment.entity.Payment;

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
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null
        );
    }
}
