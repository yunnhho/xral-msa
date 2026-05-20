package com.xrail.train.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.*;
import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrainEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishReservationCreated(Reservation reservation, List<Ticket> tickets, int startIdx, int endIdx) {
        ReservationCreatedEvent event = new ReservationCreatedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservation.getReservationId(),
                reservation.getUserId(),
                reservation.getUserName(),
                tickets.isEmpty() ? null : tickets.get(0).getScheduleId(),
                tickets.stream().map(Ticket::getSeatId).toList(),
                startIdx,
                endIdx,
                reservation.getTotalPrice(),
                reservation.getExpiresAt().toString()
        );
        kafkaTemplate.send(Topics.RESERVATION_CREATED, String.valueOf(reservation.getReservationId()), event);
        log.info("Published reservation.created reservationId={}", reservation.getReservationId());
    }

    public void publishSeatLocked(Reservation reservation, List<Long> seatIds, Long scheduleId) {
        SeatLockedEvent event = new SeatLockedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservation.getReservationId(),
                scheduleId,
                seatIds
        );
        kafkaTemplate.send(Topics.SEAT_LOCKED, String.valueOf(reservation.getReservationId()), event);
    }

    public void publishSeatLockFailed(Long reservationId, Long userId, Long scheduleId, List<Long> seatIds, String reason) {
        SeatLockFailedEvent event = new SeatLockFailedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservationId,
                userId,
                scheduleId,
                seatIds,
                reason
        );
        kafkaTemplate.send(Topics.SEAT_LOCK_FAILED, String.valueOf(reservationId), event);
    }

    public void publishSeatConfirmed(Reservation reservation, List<Ticket> tickets) {
        SeatConfirmedEvent event = new SeatConfirmedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservation.getReservationId(),
                reservation.getUserId(),
                tickets.stream().map(Ticket::getTicketId).toList()
        );
        kafkaTemplate.send(Topics.SEAT_CONFIRMED, String.valueOf(reservation.getReservationId()), event);
        log.info("Published seat.confirmed reservationId={}", reservation.getReservationId());
    }

    public void publishSeatReleased(Reservation reservation, List<Ticket> tickets, String reason) {
        Long scheduleId = tickets.isEmpty() ? null : tickets.get(0).getScheduleId();
        Integer startIdx = tickets.isEmpty() ? null : tickets.get(0).getStartStationIdx();
        Integer endIdx = tickets.isEmpty() ? null : tickets.get(0).getEndStationIdx();
        SeatReleasedEvent event = new SeatReleasedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservation.getReservationId(),
                reservation.getUserId(),
                scheduleId,
                tickets.stream().map(Ticket::getSeatId).toList(),
                startIdx,
                endIdx,
                reason
        );
        kafkaTemplate.send(Topics.SEAT_RELEASED, String.valueOf(reservation.getReservationId()), event);
        log.info("Published seat.released reservationId={} reason={}", reservation.getReservationId(), reason);
    }

    public void publishSeatReleasedReconcile(Long scheduleId, Long seatId) {
        SeatReleasedEvent event = new SeatReleasedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                null,
                null,
                null,
                scheduleId,
                List.of(seatId),
                null,
                null,
                "RECONCILE"
        );
        kafkaTemplate.send(Topics.SEAT_RELEASED, String.valueOf(scheduleId), event);
        log.info("Published seat.released scheduleId={} seatId={} reason=RECONCILE", scheduleId, seatId);
    }
}
