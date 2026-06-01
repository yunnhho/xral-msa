ALTER TABLE payments
    ADD COLUMN coupon_code VARCHAR(50) NULL AFTER failure_reason,
    ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0 AFTER coupon_code;
