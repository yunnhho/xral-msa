-- ============================================================
-- xrail_payment schema — V1
-- ============================================================

CREATE TABLE IF NOT EXISTS payments (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id    BIGINT       NOT NULL COMMENT 'train-service 소유, FK 없음',
    user_id           BIGINT       NOT NULL COMMENT 'auth-service 소유, FK 없음',
    amount            BIGINT       NOT NULL COMMENT '원 단위',
    status            VARCHAR(20)  NOT NULL COMMENT 'REQUESTED|COMPLETED|FAILED|CANCELLED',
    method            VARCHAR(20)  NOT NULL COMMENT 'CARD|BANK|MOCK',
    idempotency_key   VARCHAR(100) NOT NULL UNIQUE,
    provider_txn_id   VARCHAR(100) COMMENT 'PG 거래번호',
    failure_reason    VARCHAR(255) COMMENT '실패 원인',
    version           BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA @Version',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uk_payments_idem       ON payments (idempotency_key);
CREATE        INDEX idx_payments_reservation ON payments (reservation_id);
CREATE        INDEX idx_payments_user_status ON payments (user_id, status, created_at);
