-- ============================================================
-- xrail_train schema — V5: Transactional Outbox (P4)
-- 비즈니스 트랜잭션과 동일 트랜잭션에서 이벤트를 기록 → relay가 Kafka로 발행
-- ============================================================

CREATE TABLE outbox_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(50)  NOT NULL COMMENT '예: Reservation',
    aggregate_id    VARCHAR(100) NOT NULL COMMENT '집계 식별자 (reservationId 등)',
    topic           VARCHAR(100) NOT NULL,
    event_key       VARCHAR(100) NOT NULL COMMENT 'Kafka 파티션 키',
    event_type      VARCHAR(255) NOT NULL COMMENT '이벤트 FQCN (역직렬화용)',
    event_id        VARCHAR(100) NOT NULL COMMENT '이벤트 UUID (컨슈머 멱등 키)',
    payload         TEXT         NOT NULL COMMENT '이벤트 JSON',
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING|SENT',
    sent_at         DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- relay 폴링용: PENDING 이벤트를 id 순으로 조회
CREATE INDEX idx_outbox_status_id ON outbox_events (status, id);
