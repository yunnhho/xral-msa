-- ============================================================
-- xrail_auth schema — V1
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    user_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    dtype       VARCHAR(31)  NOT NULL COMMENT 'Member|NonMember',
    role        VARCHAR(20)  NOT NULL COMMENT 'ROLE_MEMBER|ROLE_ADMIN|ROLE_NON_MEMBER',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS members (
    user_id          BIGINT       PRIMARY KEY,
    login_id         VARCHAR(50)  UNIQUE COMMENT 'null for social-only',
    password_hash    VARCHAR(255) NOT NULL COMMENT 'bcrypt',
    name             VARCHAR(50)  NOT NULL,
    email            VARCHAR(100) NOT NULL UNIQUE,
    phone            VARCHAR(20),
    birth_date       VARCHAR(8)   COMMENT 'YYYYMMDD',
    social_provider  VARCHAR(20)  COMMENT 'KAKAO|NAVER',
    social_id        VARCHAR(100),
    CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_members_social ON members (social_provider, social_id);

CREATE TABLE IF NOT EXISTS non_members (
    user_id      BIGINT      PRIMARY KEY,
    name         VARCHAR(50) NOT NULL,
    phone        VARCHAR(20) NOT NULL,
    password     VARCHAR(6)  NOT NULL COMMENT '4-6자 숫자 (bcrypt 처리)',
    access_code  VARCHAR(10) NOT NULL UNIQUE COMMENT '10자 nanoid',
    CONSTRAINT fk_non_members_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_non_member_access ON non_members (access_code);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE COMMENT 'SHA-256, 평문 저장 금지',
    expires_at    DATETIME(6)  NOT NULL,
    rotated_from  BIGINT       COMMENT '직전 토큰 id (회전 체인)',
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(255),
    created_at    DATETIME(6)  NOT NULL,
    revoked_at    DATETIME(6)  COMMENT '명시 폐기 시',
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_refresh_user ON refresh_tokens (user_id, expires_at);
