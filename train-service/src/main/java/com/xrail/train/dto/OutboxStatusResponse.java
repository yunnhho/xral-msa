package com.xrail.train.dto;

/**
 * Transactional Outbox 건강 상태. pending이 쌓이거나 oldestPendingAgeSeconds가 커지면
 * relay 발행이 지연/중단된 신호다.
 */
public record OutboxStatusResponse(
        long pending,
        long sent,
        Long oldestPendingAgeSeconds  // PENDING이 없으면 null
) {}
