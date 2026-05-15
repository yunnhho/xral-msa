package com.xrail.common.kafka.event;

public record PaymentFailedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long paymentId,
        Long reservationId,
        Long userId,
        Long amount,
        String reason
) {}
