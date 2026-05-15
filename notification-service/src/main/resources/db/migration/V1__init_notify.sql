-- ============================================================
-- xrail_notify schema — V1
-- ============================================================

CREATE TABLE IF NOT EXISTS notification_logs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    channel          VARCHAR(20)  NOT NULL COMMENT 'SMS|EMAIL|PUSH|INAPP',
    template         VARCHAR(50)  NOT NULL COMMENT 'RESERVATION_CREATED|PAYMENT_COMPLETED|PAYMENT_FAILED|SEAT_RELEASED_TIMEOUT|WELCOME',
    correlation_id   VARCHAR(36)  NOT NULL COMMENT '원천 Kafka 이벤트 eventId (UUID)',
    payload_json     TEXT         NOT NULL,
    status           VARCHAR(10)  NOT NULL COMMENT 'PENDING|SENT|FAILED',
    failure_reason   VARCHAR(255),
    sent_at          DATETIME(6),
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uk_notify_correlation ON notification_logs (correlation_id, channel);
CREATE        INDEX idx_notify_user       ON notification_logs (user_id, created_at DESC);
