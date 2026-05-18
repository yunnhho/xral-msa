package com.xrail.train.repository;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    List<Reservation> findByUserId(Long userId);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime now);
}
