# train-service — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 train-service 작업 시 추가로 지킬 규칙이다.

## 역할

노선·스케줄·예약·티켓 관리. **Saga 보상의 최종 책임자**. Lua 비트마스크로 좌석 동시성 제어.

## 규칙

### T1. Lua 비트마스크 (핵심)
- 좌석 잠금/해제는 반드시 `reserve_seat.lua` / `rollback_seat.lua`를 통해서만. 직접 `SETBIT` 호출 금지.
- 키 패턴 `sch:{scheduleId}:seat:{seatId}` 고정. 변경 시 ERD.md §5.2와 동기화.
- 비트 인덱스 = `[startStationIdx, endStationIdx - 1]` (endIdx는 exclusive). 이 계산을 서비스 레이어에서 하는 게 아니라 `RedisService`가 단일 책임으로 처리.
- Lua 스크립트는 atomic — 트랜잭션 내에서 결과(0 or 1)를 보고 분기. 실패 시 지금까지 잡은 비트를 즉시 `rollback_seat.lua`로 해제 후 `SEAT_ALREADY_TAKEN`.

### T2. 예약 생성 — 3중 안전망 순서
1. `QueueTokenInterceptor` — HMAC 검증 (실패 시 401)
2. `IdempotencyInterceptor` — Redis SETNX 5분 (중복 시 기존 응답 반환)
3. `@Transactional` 내에서: Lua 비트마스크 → DB 더블체크(`existsOverlap`) → INSERT
- 이 순서를 변경하거나 단계를 생략하면 중복 예약이 발생한다.
- DB 더블체크를 "Lua가 이미 했으니 필요 없다"며 제거하지 않는다 — Redis 장애 복구 시 정합성 최후 방어선.

### T3. Saga 보상 책임
- `payment.failed` 수신 시: `rollback_seat.lua` → `Reservation.status = CANCELLED` → `seat.released(PAYMENT_FAILED)` emit. 이 세 단계는 하나의 트랜잭션.
- `payment.completed` 컨슈머는 멱등: `Reservation.status`가 이미 `PAID`면 no-op 후 정상 커밋.
- 보상 이벤트 emit 실패 시 예외를 삼키지 않는다 — Kafka retry → DLT로 격리.

### T4. Scheduler 규칙
- `ReservationScheduler` (60초 주기): `status=PENDING AND expires_at < now()` 배치 조회 → 건당 Lua rollback + CANCELLED + `seat.released(TIMEOUT)` emit.
- `ReservationReconciliationScheduler` (5분 주기): Redis bitmask vs DB PAID/RESERVED 비교. 고립 비트만 해제. DB 데이터를 Redis에 맞추는 방향이 아닌, **DB가 진실(source of truth)**.
- 스케줄러 작업 중 예외 발생 시 해당 건만 skip하고 다음 건 계속 처리. 전체 배치가 롤백되지 않도록.

### T5. Saga 로그
- `ReservationSagaLog`에 OUTBOUND(emit)/INBOUND(consume) 이벤트를 모두 기록.
- 로그는 디버깅 전용 — 비즈니스 로직의 흐름 제어에 사용 금지.
- `payload_json`에는 이벤트 전체를 직렬화. 민감 정보(비밀번호 등)가 포함되지 않도록 이벤트 DTO를 설계.

### T6. QueryDSL
- 단순 PK 조회는 Spring Data JPA 메서드명 쿼리 사용. QueryDSL은 동적 쿼리(다중 조건 검색)에만.
- `QEntity`(Q클래스) 직접 생성 금지 — Gradle annotationProcessor가 자동 생성.
- N+1 문제: 스케줄 목록 조회 시 `fetch join` 또는 `@EntityGraph` 사용. 루프 내 쿼리 금지.

### T7. 스키마 격리
- `xrail_train` 스키마만 접근. `user_id`는 Long 컬럼으로만 보유 (auth 스키마 조회 금지).
- `payment.completed` 이벤트의 `amount` 필드로 결제 금액 참조. payments 테이블 직접 조회 금지.
