package com.xrail.common.kafka.event;

import java.util.List;

public record ReservationCreatedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long userId,
        String userName,
        Long scheduleId,
        List<Long> seatIds,
        Integer startStationIdx,
        Integer endStationIdx,
        Long totalPrice,
        String expiresAt
) {}
