package com.xrail.train.scheduler;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.enums.ReservationStatus;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirySchedulerTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationService reservationService;

    @InjectMocks
    private ReservationExpiryScheduler scheduler;

    @Test
    void noExpiredReservations_doesNothing() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.expireStaleReservations();

        verify(reservationService, never()).expireReservation(any());
    }

    @Test
    void twoExpiredReservations_bothExpired() {
        Reservation r1 = mock(Reservation.class);
        Reservation r2 = mock(Reservation.class);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(r1, r2));

        scheduler.expireStaleReservations();

        verify(reservationService).expireReservation(r1);
        verify(reservationService).expireReservation(r2);
    }

    @Test
    void firstReservationFails_secondStillProcessed() {
        Reservation r1 = mock(Reservation.class);
        Reservation r2 = mock(Reservation.class);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(r1, r2));
        doThrow(new RuntimeException("DB error")).when(reservationService).expireReservation(r1);

        // Should not throw — error is logged and processing continues (T4 rule)
        scheduler.expireStaleReservations();

        verify(reservationService).expireReservation(r1);
        verify(reservationService).expireReservation(r2); // still called
    }
}
