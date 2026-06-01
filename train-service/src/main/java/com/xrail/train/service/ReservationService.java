package com.xrail.train.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.kafka.Topics;
import com.xrail.train.dto.ReservationRequest;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Seat;
import com.xrail.train.entity.Ticket;
import com.xrail.train.entity.enums.ReservationStatus;
import com.xrail.train.kafka.TrainEventProducer;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final int EXPIRES_MINUTES = 20;
    private static final long BASE_PRICE_PER_SEGMENT = 10_000L;

    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final ScheduleRepository scheduleRepository;
    private final RouteStationRepository routeStationRepository;
    private final SeatRepository seatRepository;
    private final LuaScriptService luaScriptService;
    private final TrainEventProducer eventProducer;
    private final SagaLogService sagaLogService;

    @Transactional
    public ReservationResponse create(Long userId, String userName, ReservationRequest request, String idempotencyKey) {
        // 1. Idempotency 확인
        if (idempotencyKey != null) {
            return reservationRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> {
                        List<Ticket> tickets = ticketRepository.findByReservationReservationId(existing.getReservationId());
                        return toResponse(existing, tickets);
                    })
                    .orElseGet(() -> doCreate(userId, userName, request, idempotencyKey));
        }
        return doCreate(userId, userName, request, idempotencyKey);
    }

    private ReservationResponse doCreate(Long userId, String userName, ReservationRequest request, String idempotencyKey) {
        Schedule schedule = scheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        Long routeId = schedule.getRoute().getRouteId();
        int startIdx = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, request.departureStationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_IN_ROUTE))
                .getStationSequence();
        int endIdx = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, request.arrivalStationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_IN_ROUTE))
                .getStationSequence();

        // 2. Lua bitmask atomic lock — with rollback on partial failure (T1)
        List<Long> lockedSeats = new ArrayList<>();
        for (Long seatId : request.seatIds()) {
            // DB double-check (T2 — last line of defence): CANCELLED 티켓은 충돌 제외
            boolean dbConflict = ticketRepository
                    .existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThanAndStatusIn(
                            request.scheduleId(), seatId, endIdx, startIdx,
                            List.of(com.xrail.train.entity.enums.TicketStatus.RESERVED,
                                    com.xrail.train.entity.enums.TicketStatus.ISSUED));
            if (dbConflict) {
                // Bug #2 fix: Redis bitmask가 누락된 경우 DB 충돌 발견 시 즉시 복원
                luaScriptService.tryReserve(request.scheduleId(), seatId, startIdx, endIdx);
                // 이미 잠근 좌석들만 롤백 (방금 복원한 seatId는 lockedSeats에 없으므로 유지됨)
                rollbackLockedSeats(lockedSeats, request.scheduleId(), startIdx, endIdx);
                throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);
            }

            boolean luaSuccess = luaScriptService.tryReserve(request.scheduleId(), seatId, startIdx, endIdx);
            if (!luaSuccess) {
                rollbackLockedSeats(lockedSeats, request.scheduleId(), startIdx, endIdx);
                throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);
            }
            lockedSeats.add(seatId);
        }

        // 3. Compute price
        long price = calculatePrice(schedule, startIdx, endIdx, request.seatIds().size());

        // 4. DB INSERT reservation + tickets
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .userId(userId)
                .userName(userName)
                .totalPrice(price)
                .idempotencyKey(idempotencyKey)
                .reservedAt(now)
                .expiresAt(now.plusMinutes(EXPIRES_MINUTES))
                .build());

        List<Ticket> tickets = new ArrayList<>();
        for (Long seatId : request.seatIds()) {
            tickets.add(ticketRepository.save(Ticket.builder()
                    .reservation(reservation)
                    .scheduleId(request.scheduleId())
                    .seatId(seatId)
                    .startStationId(request.departureStationId())
                    .endStationId(request.arrivalStationId())
                    .startStationIdx(startIdx)
                    .endStationIdx(endIdx)
                    .price(price / request.seatIds().size())
                    .build()));
        }

        // 5. Saga log + Kafka emit
        MDC.put("userId", String.valueOf(userId));
        eventProducer.publishReservationCreated(reservation, tickets, startIdx, endIdx);
        eventProducer.publishSeatLocked(reservation, request.seatIds(), request.scheduleId());
        try {
            sagaLogService.recordOutbound(reservation.getReservationId(), Topics.RESERVATION_CREATED, reservation.getReservationId());
        } catch (Exception e) {
            // Saga log is non-critical; do not roll back the reservation
            log.warn("Saga log write failed reservationId={} reason={}", reservation.getReservationId(), e.getMessage());
        }

        log.info("Reservation created reservationId={} userId={} seats={}", reservation.getReservationId(), userId, request.seatIds());
        return toResponse(reservation, tickets);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getById(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        List<Ticket> tickets = ticketRepository.findByReservationReservationId(reservationId);
        return toResponse(reservation, tickets);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> listByUser(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(r -> {
                    List<Ticket> tickets = ticketRepository.findByReservationReservationId(r.getReservationId());
                    return toResponse(r, tickets);
                })
                .toList();
    }

    @Transactional
    public void cancelByUser(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) return;
        List<Ticket> tickets = ticketRepository.findByReservationReservationId(reservationId);
        tickets.forEach(Ticket::cancel);
        for (Ticket t : tickets) {
            luaScriptService.rollback(t.getScheduleId(), t.getSeatId(), t.getStartStationIdx(), t.getEndStationIdx());
        }
        reservation.cancel();
        eventProducer.publishSeatReleased(reservation, tickets, "USER_CANCEL");
        try {
            sagaLogService.recordOutbound(reservationId, Topics.SEAT_RELEASED, "USER_CANCEL");
        } catch (Exception e) {
            log.warn("Saga log write failed reservationId={} reason={}", reservationId, e.getMessage());
        }
        log.info("Reservation cancelled by user reservationId={} userId={}", reservationId, userId);
    }

    @Transactional
    public void expireReservation(Reservation reservation) {
        List<Ticket> tickets = ticketRepository.findByReservationReservationId(reservation.getReservationId());
        tickets.forEach(Ticket::cancel);
        for (Ticket t : tickets) {
            luaScriptService.rollback(t.getScheduleId(), t.getSeatId(), t.getStartStationIdx(), t.getEndStationIdx());
        }
        reservation.cancel();
        eventProducer.publishSeatReleased(reservation, tickets, "TIMEOUT");
        try {
            sagaLogService.recordOutbound(reservation.getReservationId(), Topics.SEAT_RELEASED, "TIMEOUT");
        } catch (Exception e) {
            log.warn("Saga log write failed reservationId={} reason={}", reservation.getReservationId(), e.getMessage());
        }
        log.info("Reservation expired reservationId={}", reservation.getReservationId());
    }

    @Transactional
    public void handlePaymentCompleted(Long reservationId) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            if (reservation.getStatus() == ReservationStatus.PAID) {
                return; // already paid — idempotent
            }
            reservation.markPaid();
            List<Ticket> tickets = ticketRepository.findByReservationReservationId(reservationId);
            tickets.forEach(Ticket::issue);
            eventProducer.publishSeatConfirmed(reservation, tickets);
            try {
                sagaLogService.recordInbound(reservationId, Topics.PAYMENT_COMPLETED, reservationId);
            } catch (Exception e) {
                log.warn("Saga log write failed reservationId={} reason={}", reservationId, e.getMessage());
            }
        });
    }

    @Transactional
    public void handlePaymentFailed(Long reservationId) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            List<Ticket> tickets = ticketRepository.findByReservationReservationId(reservationId);
            tickets.forEach(Ticket::cancel);
            for (Ticket t : tickets) {
                luaScriptService.rollback(t.getScheduleId(), t.getSeatId(), t.getStartStationIdx(), t.getEndStationIdx());
            }
            reservation.cancel();
            eventProducer.publishSeatReleased(reservation, tickets, "PAYMENT_FAILED");
            try {
                sagaLogService.recordInbound(reservationId, Topics.PAYMENT_FAILED, reservationId);
            } catch (Exception e) {
                log.warn("Saga log write failed reservationId={} reason={}", reservationId, e.getMessage());
            }
            log.info("Reservation cancelled due to payment failure reservationId={}", reservationId);
        });
    }

    private ReservationResponse toResponse(Reservation r, List<Ticket> tickets) {
        List<Long> seatIds = tickets.stream().map(Ticket::getSeatId).toList();
        Map<Long, String> seatNumberMap = seatRepository.findAllById(seatIds).stream()
                .collect(Collectors.toMap(Seat::getSeatId, Seat::getSeatNumber));
        return ReservationResponse.of(r, tickets, seatNumberMap);
    }

    private void rollbackLockedSeats(List<Long> lockedSeats, Long scheduleId, int startIdx, int endIdx) {
        lockedSeats.forEach(seatId -> luaScriptService.rollback(scheduleId, seatId, startIdx, endIdx));
    }

    private long calculatePrice(Schedule schedule, int startIdx, int endIdx, int seatCount) {
        return BASE_PRICE_PER_SEGMENT * (endIdx - startIdx) * seatCount;
    }
}
