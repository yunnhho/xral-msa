-- ============================================================
-- xrail_train schema — V1: 마스터 데이터
-- ============================================================

CREATE TABLE IF NOT EXISTS stations (
    station_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS routes (
    route_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL COMMENT '경부선|호남선',
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_stations (
    route_station_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id             BIGINT  NOT NULL,
    station_id           BIGINT  NOT NULL,
    station_sequence     INT     NOT NULL COMMENT '0부터 시작 — Lua bitmask 인덱스',
    cumulative_distance  DOUBLE  NOT NULL DEFAULT 0.0,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    CONSTRAINT fk_rs_route   FOREIGN KEY (route_id)   REFERENCES routes   (route_id),
    CONSTRAINT fk_rs_station FOREIGN KEY (station_id) REFERENCES stations (station_id),
    UNIQUE KEY uk_route_seq (route_id, station_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trains (
    train_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    train_number  VARCHAR(20) NOT NULL UNIQUE,
    train_type    VARCHAR(20) NOT NULL COMMENT 'KTX|MUGUNGHWA|SAEMAUL|ITX',
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS carriages (
    carriage_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    train_id         BIGINT  NOT NULL,
    carriage_number  INT     NOT NULL,
    seat_count       INT     NOT NULL,
    CONSTRAINT fk_carriage_train FOREIGN KEY (train_id) REFERENCES trains (train_id),
    UNIQUE KEY uk_carriage (train_id, carriage_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS seats (
    seat_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    carriage_id   BIGINT      NOT NULL,
    seat_number   VARCHAR(10) NOT NULL COMMENT '1A|1B|2A...',
    CONSTRAINT fk_seat_carriage FOREIGN KEY (carriage_id) REFERENCES carriages (carriage_id),
    UNIQUE KEY uk_seat (carriage_id, seat_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS schedules (
    schedule_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id        BIGINT  NOT NULL,
    train_id        BIGINT  NOT NULL,
    departure_date  DATE    NOT NULL,
    departure_time  TIME    NOT NULL,
    arrival_time    TIME    NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT fk_schedule_route FOREIGN KEY (route_id) REFERENCES routes (route_id),
    CONSTRAINT fk_schedule_train FOREIGN KEY (train_id) REFERENCES trains (train_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_schedule_date ON schedules (departure_date, route_id);
