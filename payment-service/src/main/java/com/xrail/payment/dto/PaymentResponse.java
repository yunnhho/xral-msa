package com.xrail.payment.dto;

import com.xrail.payment.entity.Payment;

import java.time.Instant;

public record PaymentResponse(
        Long paymentId,
        Long reservationId,
        String status,
        Long amount,
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
                p.getMethod(),
                p.getProviderTxnId(),
                p.getFailureReason(),
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null
        );
    }
}
