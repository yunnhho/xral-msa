package com.xrail.train.repository;

import com.xrail.train.entity.DltAlertLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DltAlertLogRepository extends JpaRepository<DltAlertLog, Long> {
    Page<DltAlertLog> findByTopic(String topic, Pageable pageable);
}
