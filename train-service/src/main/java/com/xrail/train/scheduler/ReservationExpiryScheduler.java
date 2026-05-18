package com.xrail.train.scheduler;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.enums.ReservationStatus;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryScheduler {

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;

    @Scheduled(fixedDelay = 60_000)
    public void expireStaleReservations() {
        List<Reservation> expired = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING, LocalDateTime.now());

        if (expired.isEmpty()) return;

        log.info("Expiring {} stale PENDING reservations", expired.size());
        for (Reservation reservation : expired) {
            try {
                reservationService.expireReservation(reservation);
            } catch (Exception e) {
                // T4: skip this one, continue with next
                log.error("Failed to expire reservationId={}", reservation.getReservationId(), e);
            }
        }
    }
}
