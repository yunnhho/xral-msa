package com.xrail.payment.repository;

import com.xrail.payment.entity.DltAlertLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DltAlertLogRepository extends JpaRepository<DltAlertLog, Long> {
    Page<DltAlertLog> findByTopic(String topic, Pageable pageable);
}
