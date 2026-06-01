CREATE TABLE payment_dlt_alert_logs (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    topic         VARCHAR(100) NOT NULL,
    `partition`   INT          NOT NULL,
    `offset`      BIGINT       NOT NULL,
    record_key    VARCHAR(255),
    record_value  TEXT,
    error_message VARCHAR(500),
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
