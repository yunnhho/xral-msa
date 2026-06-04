package com.xrail.train.repository;

import com.xrail.train.entity.Reservation;
import com.xrail.train.entity.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    List<Reservation> findByUserId(Long userId);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime now);

    // 운영자 모니터링용
    long countByStatus(ReservationStatus status);

    Page<Reservation> findByStatus(ReservationStatus status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(r.totalPrice), 0) FROM Reservation r WHERE r.status = :status")
    long sumTotalPriceByStatus(ReservationStatus status);
}
