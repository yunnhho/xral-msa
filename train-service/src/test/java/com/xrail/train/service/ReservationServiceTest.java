package com.xrail.train.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.train.dto.ReservationRequest;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.Route;
import com.xrail.train.entity.RouteStation;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Seat;
import com.xrail.train.entity.Ticket;
import com.xrail.train.entity.enums.ReservationStatus;
import com.xrail.train.entity.enums.TicketStatus;
import com.xrail.train.kafka.TrainEventProducer;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private RouteStationRepository routeStationRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private LuaScriptService luaScriptService;
    @Mock private TrainEventProducer eventProducer;
    @Mock private SagaLogService sagaLogService;

    @InjectMocks
    private ReservationService reservationService;

    private Schedule mockSchedule;
    private RouteStation mockDepRS;
    private RouteStation mockArrRS;
    private Reservation savedReservation;

    @BeforeEach
    void setUp() {
        Route route = mock(Route.class);
        lenient().when(route.getRouteId()).thenReturn(100L);

        mockSchedule = mock(Schedule.class);
        lenient().when(mockSchedule.getRoute()).thenReturn(route);
        lenient().when(mockSchedule.getScheduleId()).thenReturn(1L);
        // 기본값: 내일 출발 (예약 가능)
        lenient().when(mockSchedule.getDepartureDate()).thenReturn(LocalDate.now().plusDays(1));
        lenient().when(mockSchedule.getDepartureTime()).thenReturn(LocalTime.of(9, 0));

        mockDepRS = mock(RouteStation.class);
        lenient().when(mockDepRS.getStationSequence()).thenReturn(0);

        mockArrRS = mock(RouteStation.class);
        lenient().when(mockArrRS.getStationSequence()).thenReturn(3);

        savedReservation = Reservation.builder()
                .userId(42L)
                .userName("테스트")
                .totalPrice(30_000L)
                .idempotencyKey("idem-key-1")
                .reservedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(20))
                .build();
    }

    // ===== create — idempotency =====

    @Test
    void create_existingIdempotencyKey_returnsExistingReservation() {
        Reservation existing = savedReservation;
        when(reservationRepository.findByIdempotencyKey("idem-key-1"))
                .thenReturn(Optional.of(existing));
        when(ticketRepository.findByReservationReservationId(any())).thenReturn(List.of());
        when(seatRepository.findAllById(anyList())).thenReturn(List.of());

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));
        ReservationResponse response = reservationService.create(42L, "테스트", request, "idem-key-1");

        assertThat(response).isNotNull();
        verify(luaScriptService, never()).tryReserve(anyLong(), anyLong(), any(int.class), any(int.class));
    }

    // ===== create — success flow =====

    @Test
    void create_luaSuccess_persistsReservation() {
        when(reservationRepository.findByIdempotencyKey("new-key")).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 10L))
                .thenReturn(Optional.of(mockDepRS));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 20L))
                .thenReturn(Optional.of(mockArrRS));
        when(ticketRepository.existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThanAndStatusIn(
                anyLong(), anyLong(), any(int.class), any(int.class), anyList()))
                .thenReturn(false);
        when(luaScriptService.tryReserve(anyLong(), anyLong(), any(int.class), any(int.class)))
                .thenReturn(true);
        when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seatRepository.findAllById(anyList())).thenReturn(List.of());

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));
        ReservationResponse response = reservationService.create(42L, "테스트", request, "new-key");

        assertThat(response).isNotNull();
        verify(luaScriptService).tryReserve(1L, 5L, 0, 3);
        verify(reservationRepository).save(any(Reservation.class));
    }

    // ===== create — Lua conflict =====

    @Test
    void create_luaFail_throwsSeatAlreadyTaken() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 10L))
                .thenReturn(Optional.of(mockDepRS));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 20L))
                .thenReturn(Optional.of(mockArrRS));
        when(ticketRepository.existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThanAndStatusIn(
                anyLong(), anyLong(), any(int.class), any(int.class), anyList()))
                .thenReturn(false);
        when(luaScriptService.tryReserve(anyLong(), anyLong(), any(int.class), any(int.class)))
                .thenReturn(false); // conflict

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN));

        verify(reservationRepository, never()).save(any());
    }

    // ===== create — partial lock rollback =====

    @Test
    void create_luaFailOnSecondSeat_rollbacksFirstSeat() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 10L))
                .thenReturn(Optional.of(mockDepRS));
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 20L))
                .thenReturn(Optional.of(mockArrRS));
        when(ticketRepository.existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThanAndStatusIn(
                anyLong(), anyLong(), any(int.class), any(int.class), anyList()))
                .thenReturn(false);
        when(luaScriptService.tryReserve(1L, 5L, 0, 3)).thenReturn(true);  // seat 5 ok
        when(luaScriptService.tryReserve(1L, 6L, 0, 3)).thenReturn(false); // seat 6 conflict

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L, 6L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN));

        // Seat 5 was locked → must be rolled back
        verify(luaScriptService).rollback(1L, 5L, 0, 3);
    }

    // ===== create — reservation rules =====

    @Test
    void create_pastDeparture_throwsLateReservation() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        when(mockSchedule.getDepartureDate()).thenReturn(LocalDate.now().minusDays(1));

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.LATE_RESERVATION));

        verify(luaScriptService, never()).tryReserve(anyLong(), anyLong(), any(int.class), any(int.class));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_imminentDeparture_throwsLateReservation() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        // 출발 5분 전 — 마감(10분) 이내
        LocalDateTime soon = LocalDateTime.now().plusMinutes(5);
        when(mockSchedule.getDepartureDate()).thenReturn(soon.toLocalDate());
        when(mockSchedule.getDepartureTime()).thenReturn(soon.toLocalTime());

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.LATE_RESERVATION));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_sameDepartureAndArrival_throwsInvalidRoute() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));

        ReservationRequest request = new ReservationRequest(1L, 10L, 10L, List.of(5L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ROUTE));

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_reverseDirection_throwsInvalidRoute() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(mockSchedule));
        // 출발역 시퀀스(3) > 도착역 시퀀스(0) — 역방향
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 10L))
                .thenReturn(Optional.of(mockArrRS)); // seq 3
        when(routeStationRepository.findByRouteRouteIdAndStationStationId(100L, 20L))
                .thenReturn(Optional.of(mockDepRS)); // seq 0

        ReservationRequest request = new ReservationRequest(1L, 10L, 20L, List.of(5L));

        assertThatThrownBy(() -> reservationService.create(42L, "테스트", request, "new-key"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ROUTE));

        verify(luaScriptService, never()).tryReserve(anyLong(), anyLong(), any(int.class), any(int.class));
        verify(reservationRepository, never()).save(any());
    }

    // ===== getById =====

    @Test
    void getById_wrongUser_throwsForbidden() {
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(savedReservation));
        // savedReservation.userId = 42L, but querying as user 99L
        assertThatThrownBy(() -> reservationService.getById(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ===== cancelByAdmin — force cancel =====

    @Test
    void cancelByAdmin_rollbacksSeatsAndPublishesAdminCancel() {
        Ticket ticket = mock(Ticket.class);
        when(ticket.getScheduleId()).thenReturn(1L);
        when(ticket.getSeatId()).thenReturn(5L);
        when(ticket.getStartStationIdx()).thenReturn(0);
        when(ticket.getEndStationIdx()).thenReturn(3);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(savedReservation));
        when(ticketRepository.findByReservationReservationId(any())).thenReturn(List.of(ticket));

        reservationService.cancelByAdmin(1L);

        verify(luaScriptService).rollback(1L, 5L, 0, 3);
        verify(eventProducer).publishSeatReleased(any(), anyList(), eq("ADMIN_CANCEL"));
        assertThat(savedReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        // PENDING(미결제) 예약 취소는 환불 사가를 트리거하지 않는다
        verify(eventProducer, never()).publishPaymentRefundRequested(any(), any());
    }

    // ===== 환불 사가 트리거 =====

    @Test
    void cancelByUser_paidReservation_triggersRefund() {
        Ticket ticket = mock(Ticket.class);
        when(ticket.getScheduleId()).thenReturn(1L);
        when(ticket.getSeatId()).thenReturn(5L);
        when(ticket.getStartStationIdx()).thenReturn(0);
        when(ticket.getEndStationIdx()).thenReturn(3);
        Reservation paid = mock(Reservation.class);
        when(paid.getStatus()).thenReturn(ReservationStatus.PAID);
        when(paid.getReservationId()).thenReturn(1L);
        when(paid.getUserId()).thenReturn(42L);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(paid));
        when(ticketRepository.findByReservationReservationId(any())).thenReturn(List.of(ticket));

        reservationService.cancelByUser(1L, 42L);

        // 좌석 즉시 반환 + 환불 요청 발행
        verify(luaScriptService).rollback(1L, 5L, 0, 3);
        verify(eventProducer).publishSeatReleased(any(), anyList(), eq("USER_CANCEL"));
        verify(eventProducer).publishPaymentRefundRequested(eq(paid), eq("USER_CANCEL"));
    }

    @Test
    void handleRefunded_recordsSagaLogOnly() {
        reservationService.handleRefunded(1L);

        verify(sagaLogService).recordInbound(eq(1L), any(), any());
        verify(luaScriptService, never()).rollback(anyLong(), anyLong(), any(int.class), any(int.class));
    }

    @Test
    void cancelByAdmin_alreadyCancelled_noOp() {
        Reservation cancelled = mock(Reservation.class);
        when(cancelled.getStatus()).thenReturn(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(cancelled));

        reservationService.cancelByAdmin(1L);

        verify(luaScriptService, never()).rollback(anyLong(), anyLong(), any(int.class), any(int.class));
        verify(eventProducer, never()).publishSeatReleased(any(), anyList(), any());
    }

    // ===== handlePaymentCompleted — idempotency =====

    @Test
    void handlePaymentCompleted_alreadyPaid_noOp() {
        Reservation paid = mock(Reservation.class);
        when(paid.getStatus()).thenReturn(ReservationStatus.PAID);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(paid));

        reservationService.handlePaymentCompleted(1L);

        verify(paid, never()).markPaid();
        verify(eventProducer, never()).publishSeatConfirmed(any(), anyList());
    }

    // ===== expireReservation =====

    @Test
    void expireReservation_rollbacksSeatsAndCancels() {
        Ticket ticket = mock(Ticket.class);
        when(ticket.getScheduleId()).thenReturn(1L);
        when(ticket.getSeatId()).thenReturn(5L);
        when(ticket.getStartStationIdx()).thenReturn(0);
        when(ticket.getEndStationIdx()).thenReturn(3);
        // expireReservation re-fetches inside the transaction (detached-entity fix)
        when(reservationRepository.findById(any())).thenReturn(Optional.of(savedReservation));
        when(ticketRepository.findByReservationReservationId(any())).thenReturn(List.of(ticket));

        reservationService.expireReservation(savedReservation);

        verify(luaScriptService).rollback(1L, 5L, 0, 3);
        verify(eventProducer).publishSeatReleased(any(), anyList(), eq("TIMEOUT"));
    }

    @Test
    void expireReservation_alreadyPaid_noOp() {
        // Race guard: reservation became PAID after the scheduler's query → must not cancel
        Reservation paid = mock(Reservation.class);
        when(paid.getStatus()).thenReturn(ReservationStatus.PAID);
        when(reservationRepository.findById(any())).thenReturn(Optional.of(paid));

        reservationService.expireReservation(savedReservation);

        verify(luaScriptService, never()).rollback(anyLong(), anyLong(), any(int.class), any(int.class));
        verify(eventProducer, never()).publishSeatReleased(any(), anyList(), any());
    }

    // ===== handlePaymentFailed =====

    @Test
    void handlePaymentFailed_rollbacksAndPublishesEvent() {
        Ticket ticket = mock(Ticket.class);
        when(ticket.getScheduleId()).thenReturn(1L);
        when(ticket.getSeatId()).thenReturn(5L);
        when(ticket.getStartStationIdx()).thenReturn(0);
        when(ticket.getEndStationIdx()).thenReturn(3);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(savedReservation));
        when(ticketRepository.findByReservationReservationId(any())).thenReturn(List.of(ticket));

        reservationService.handlePaymentFailed(1L);

        verify(luaScriptService).rollback(1L, 5L, 0, 3);
        verify(eventProducer).publishSeatReleased(any(), anyList(), eq("PAYMENT_FAILED"));
    }

    @Test
    void handlePaymentFailed_alreadyCancelled_noOp() {
        // Idempotency guard: duplicate payment.failed must not re-rollback (would free
        // bits possibly re-acquired by another reservation)
        Reservation cancelled = mock(Reservation.class);
        when(cancelled.getStatus()).thenReturn(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(cancelled));

        reservationService.handlePaymentFailed(1L);

        verify(luaScriptService, never()).rollback(anyLong(), anyLong(), any(int.class), any(int.class));
        verify(eventProducer, never()).publishSeatReleased(any(), anyList(), any());
    }
}
