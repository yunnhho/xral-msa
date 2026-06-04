package com.xrail.train.repository;

import com.xrail.train.entity.ReservationSagaLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSagaLogRepository extends JpaRepository<ReservationSagaLog, Long> {

    Page<ReservationSagaLog> findByReservationId(Long reservationId, Pageable pageable);
}
