package com.xrail.common.kafka.event;

public record UserSignedUpEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long userId,
        String userType,
        String name,
        String email
) {}
