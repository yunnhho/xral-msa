-- ============================================================
-- xrail_train schema — V6: reservation_saga_log FK 제거
-- 사가 로그는 디버깅 전용(T5)인데 reservations FK가 있으면 REQUIRES_NEW로 도는
-- 사가 로그 INSERT가 FK 검증을 위해 부모 행 S-lock을 기다리고, 그 행의 X-lock은
-- 외부 비즈니스 트랜잭션이 쥐고 있어 자기-교착 → 3초 타임아웃으로
-- reservation.created / payment.completed 로그가 항상 유실됐다.
-- 로그 테이블에 참조 무결성 강제는 불필요하다 (인덱스는 유지).
-- ============================================================

ALTER TABLE reservation_saga_log DROP FOREIGN KEY fk_saga_reservation;
