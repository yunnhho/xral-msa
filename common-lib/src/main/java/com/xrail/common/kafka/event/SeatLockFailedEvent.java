package com.xrail.common.kafka.event;

import java.util.List;

public record SeatLockFailedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long userId,
        Long scheduleId,
        List<Long> conflictingSeatIds,
        String reason
) {}
