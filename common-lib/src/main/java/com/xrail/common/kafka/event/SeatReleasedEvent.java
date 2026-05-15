package com.xrail.common.kafka.event;

import java.util.List;

public record SeatReleasedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long userId,
        Long scheduleId,
        List<Long> seatIds,
        Integer startStationIdx,
        Integer endStationIdx,
        String reason  // PAYMENT_FAILED | TIMEOUT | USER_CANCELLED | RECONCILE
) {}
