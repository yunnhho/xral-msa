package com.xrail.common.kafka.event;

public record PaymentRequestedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long paymentId,
        Long reservationId,
        Long userId,
        Long amount,
        String method,
        String idempotencyKey
) {}
