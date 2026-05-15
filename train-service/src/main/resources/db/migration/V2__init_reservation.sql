-- ============================================================
-- xrail_train schema — V2: 예약 / 티켓 / Saga 로그
-- ============================================================

CREATE TABLE IF NOT EXISTS reservations (
    reservation_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL COMMENT '크로스 스키마라 FK 없음',
    user_name        VARCHAR(50)  COMMENT 'X-User-Name 스냅샷',
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING|PAID|CANCELLED',
    total_price      BIGINT       NOT NULL,
    idempotency_key  VARCHAR(100) UNIQUE COMMENT 'POST /api/reservations 중복 방지',
    reserved_at      DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL COMMENT 'reserved_at + 20분',
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reservation_user    ON reservations (user_id);
CREATE INDEX idx_reservation_expire  ON reservations (status, expires_at);
CREATE INDEX idx_reservation_idem    ON reservations (user_id, idempotency_key);

CREATE TABLE IF NOT EXISTS tickets (
    ticket_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id     BIGINT      NOT NULL,
    schedule_id        BIGINT      NOT NULL,
    seat_id            BIGINT      NOT NULL,
    start_station_id   BIGINT      NOT NULL,
    end_station_id     BIGINT      NOT NULL,
    start_station_idx  INT         NOT NULL COMMENT 'segment bit start',
    end_station_idx    INT         NOT NULL COMMENT 'segment bit end (exclusive)',
    price              BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL COMMENT 'RESERVED|ISSUED|CANCELLED|USED',
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT fk_ticket_reservation   FOREIGN KEY (reservation_id)   REFERENCES reservations (reservation_id),
    CONSTRAINT fk_ticket_schedule      FOREIGN KEY (schedule_id)      REFERENCES schedules    (schedule_id),
    CONSTRAINT fk_ticket_seat          FOREIGN KEY (seat_id)          REFERENCES seats        (seat_id),
    CONSTRAINT fk_ticket_start_station FOREIGN KEY (start_station_id) REFERENCES stations     (station_id),
    CONSTRAINT fk_ticket_end_station   FOREIGN KEY (end_station_id)   REFERENCES stations     (station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ticket_segment ON tickets (schedule_id, seat_id);
CREATE INDEX idx_ticket_status  ON tickets (status);

CREATE TABLE IF NOT EXISTS reservation_saga_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id  BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL COMMENT 'reservation.created|seat.locked|payment.completed|...',
    direction       VARCHAR(10)  NOT NULL COMMENT 'OUTBOUND|INBOUND',
    payload_json    TEXT         NOT NULL,
    observed_at     DATETIME(6)  NOT NULL,
    CONSTRAINT fk_saga_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_saga_reservation ON reservation_saga_log (reservation_id, observed_at);
