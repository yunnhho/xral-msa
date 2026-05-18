package com.xrail.train.dto;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Ticket;

import java.time.LocalDateTime;
import java.util.List;

public record ReservationResponse(
        Long reservationId,
        Long userId,
        String status,
        Long totalPrice,
        LocalDateTime expiresAt,
        List<Long> ticketIds
) {
    public static ReservationResponse of(Reservation r, List<Ticket> tickets) {
        return new ReservationResponse(
                r.getReservationId(),
                r.getUserId(),
                r.getStatus().name(),
                r.getTotalPrice(),
                r.getExpiresAt(),
                tickets.stream().map(Ticket::getTicketId).toList()
        );
    }
}
