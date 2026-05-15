package com.xrail.common.kafka.event;

import java.util.List;

public record SeatLockedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long scheduleId,
        List<Long> seatIds
) {}
