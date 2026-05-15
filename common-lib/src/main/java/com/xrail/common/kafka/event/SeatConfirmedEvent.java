package com.xrail.common.kafka.event;

import java.util.List;

public record SeatConfirmedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long userId,
        List<Long> ticketIds
) {}
