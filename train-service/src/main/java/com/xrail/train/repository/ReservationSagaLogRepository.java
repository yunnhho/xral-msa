package com.xrail.train.repository;

import com.xrail.train.entity.ReservationSagaLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSagaLogRepository extends JpaRepository<ReservationSagaLog, Long> {}
