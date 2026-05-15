package com.xrail.common.kafka.event;

public record PaymentCompletedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long paymentId,
        Long reservationId,
        Long userId,
        Long amount,
        String providerTxnId
) {}
