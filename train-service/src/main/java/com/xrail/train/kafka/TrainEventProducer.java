package com.xrail.train.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.*;
import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Ticket;
import com.xrail.train.service.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * P4: 모든 이벤트는 직접 Kafka로 보내지 않고 호출자의 트랜잭션 안에서 outbox에 기록한다.
 * 실제 발행은 OutboxRelayScheduler가 커밋 후 수행 — DB 커밋과 발행의 원자성 보장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainEventProducer {

    private static final String OUTBOX_AGGREGATE = "Reservation";

    private final OutboxRecorder outboxRecorder;

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
                reservation.getExpiresAt().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        );
        record(Topics.RESERVATION_CREATED, reservation.getReservationId(), event);
        log.info("Recorded reservation.created to outbox reservationId={}", reservation.getReservationId());
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
        record(Topics.SEAT_LOCKED, reservation.getReservationId(), event);
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
        record(Topics.SEAT_LOCK_FAILED, reservationId, event);
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
        record(Topics.SEAT_CONFIRMED, reservation.getReservationId(), event);
        log.info("Recorded seat.confirmed to outbox reservationId={}", reservation.getReservationId());
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
        record(Topics.SEAT_RELEASED, reservation.getReservationId(), event);
        log.info("Recorded seat.released to outbox reservationId={} reason={}", reservation.getReservationId(), reason);
    }

    public void publishPaymentRefundRequested(Reservation reservation, String reason) {
        PaymentRefundRequestedEvent event = new PaymentRefundRequestedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                reservation.getReservationId(),
                reservation.getUserId(),
                reason
        );
        record(Topics.PAYMENT_REFUND_REQUESTED, reservation.getReservationId(), event);
        log.info("Recorded payment.refund-requested to outbox reservationId={} reason={}", reservation.getReservationId(), reason);
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
        record(Topics.SEAT_RELEASED, scheduleId, event);
        log.info("Recorded seat.released to outbox scheduleId={} seatId={} reason=RECONCILE", scheduleId, seatId);
    }

    private void record(String topic, Long aggregateId, Object event) {
        outboxRecorder.record(OUTBOX_AGGREGATE, String.valueOf(aggregateId), topic,
                String.valueOf(aggregateId), event);
    }
}
