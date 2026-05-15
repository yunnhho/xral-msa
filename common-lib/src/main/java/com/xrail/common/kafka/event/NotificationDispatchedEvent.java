package com.xrail.common.kafka.event;

public record NotificationDispatchedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long notificationId,
        Long userId,
        String channel,
        String template,
        String correlationId
) {}
