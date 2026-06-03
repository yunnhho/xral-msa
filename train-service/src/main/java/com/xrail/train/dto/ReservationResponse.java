package com.xrail.train.dto;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Ticket;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public record ReservationResponse(
        Long reservationId,
        Long userId,
        String status,
        Long totalPrice,
        OffsetDateTime reservedAt,
        OffsetDateTime expiresAt,
        List<TicketSummary> tickets
) {
    public record TicketSummary(Long ticketId, Long seatId, String seatNumber, Long price) {}

    public static ReservationResponse of(Reservation r, List<Ticket> tickets, Map<Long, String> seatNumberMap) {
        List<TicketSummary> summaries = tickets.stream()
                .map(t -> new TicketSummary(
                        t.getTicketId(),
                        t.getSeatId(),
                        seatNumberMap.getOrDefault(t.getSeatId(), String.valueOf(t.getSeatId())),
                        t.getPrice()))
                .toList();
        return new ReservationResponse(
                r.getReservationId(),
                r.getUserId(),
                r.getStatus().name(),
                r.getTotalPrice(),
                r.getReservedAt().atOffset(ZoneOffset.UTC),
                r.getExpiresAt() != null ? r.getExpiresAt().atOffset(ZoneOffset.UTC) : null,
                summaries
        );
    }
}
