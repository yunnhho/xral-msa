package com.xrail.train.repository;

import com.xrail.train.entity.OutboxEvent;
import com.xrail.train.entity.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxStatus status);

    long countByStatus(OutboxStatus status);

    Optional<OutboxEvent> findFirstByStatusOrderByIdAsc(OutboxStatus status);
}
