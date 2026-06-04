package com.xrail.payment.repository;

import com.xrail.payment.entity.OutboxEvent;
import com.xrail.payment.entity.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxStatus status);

    long countByStatus(OutboxStatus status);

    Optional<OutboxEvent> findFirstByStatusOrderByIdAsc(OutboxStatus status);
}
