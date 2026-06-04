# payment-service — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 payment-service 작업 시 추가로 지킬 규칙이다.

## 역할

결제 요청·확정·실패 처리. Idempotency 버킷, `@Version` 낙관적 락, mock PG, DLT 처리.

## 규칙

### P1. Idempotency 버킷 (핵심)
- 모든 `POST /api/payments` 처리 흐름: Redisson lock 획득 → Redis 상태 확인 → 처리 → Redis 상태 갱신 → lock 해제.
- `payment:idem:{key}` 상태: `PROCESSING` → `COMPLETED` 또는 `FAILED`.
- 동일 key + 동일 payload 재요청 → `COMPLETED` 상태면 기존 응답 그대로 반환 (HTTP 200).
- 동일 key + **다른 payload** → 409 `IDEMPOTENCY_CONFLICT`.
- `PROCESSING` 상태 중 재요청 → 409 (lock 충돌). 클라이언트는 재시도하면 안 됨을 응답으로 명시.

### P2. @Version 낙관적 락
- `Payment` 엔티티에 `@Version Long version` 필드 필수. 제거 금지.
- `OptimisticLockException` 발생 시 재조회 후 현재 상태에 따라 분기. 단순 재시도 루프 금지.
- `version` 컬럼은 비즈니스 로직에서 직접 설정하지 않는다 — JPA가 관리.

### P3. Mock PG
- `payment.mock.always-fail` 환경변수로 실패 시나리오 토글.
- Mock PG 로직은 `MockPaymentGateway` 같은 별도 클래스로 분리. `PaymentService` 내부에 인라인 작성 금지.
- 실 PG 어댑터 도입 시 Mock과 동일한 인터페이스(`PaymentGateway`)를 구현. 서비스 코드 변경 최소화.

### P4. Kafka 이벤트 (Transactional Outbox)
- 모든 결제 이벤트(`payment.requested/completed/failed/refunded`)는 직접 발행하지 않고 `OutboxRecorder`로 `outbox_events`에 기록한다 — 비즈니스 트랜잭션과 같은 트랜잭션에서 INSERT되어 DB 커밋과 발행의 원자성을 보장(이벤트 유실 0). 실제 발행은 `OutboxRelayScheduler`가 커밋 후 폴링하여 수행(at-least-once → 컨슈머 멱등으로 안전).
- relay는 저장된 payload를 원래 이벤트 FQCN으로 역직렬화 후 `KafkaTemplate`으로 보낸다 — JsonSerializer `__TypeId__` 헤더가 직접 발행과 동일하게 부여되어 컨슈머 호환.
- 단일 인스턴스 가정(relay에 SKIP LOCKED 미적용). 다중 인스턴스 확장 시 중복 발행은 멱등으로 흡수되나 효율을 위해 락 도입 검토.
- `payment.requested`는 감사(audit) 전용. 다른 서비스가 이 이벤트로 비즈니스 로직을 실행하면 안 된다.

### P5. DLT 처리
- `payment.completed` 컨슈머(train-service 쪽)가 실패하면 `payment.completed.DLT`로 격리.
- DLT 메시지 수신 시 운영자에게 알림(log + 향후 Slack). 자동 재처리 로직 내부에 작성 금지.
- DLT 핸들러는 예외를 throw하지 않는다 — DLT에서 또 실패하면 무한 루프.

### P6. 스키마 격리
- `xrail_payment` 스키마만 접근. `reservation_id`는 Long 참조값일 뿐 — train 스키마 조회 금지.
- 결제 금액 검증은 `POST /api/payments` 요청 body의 `amount`와 이벤트/헤더의 값을 비교. train DB 조회로 검증하지 않는다.

### P7. 상태 전이
- `Payment.status` 전이: `REQUESTED → COMPLETED | FAILED`, `COMPLETED → CANCELLED`(환불).
- 환불은 `payment.refund-requested`(train이 PAID 예약 취소 시 발행) 컨슈머가 `PaymentService.refund`로 처리: COMPLETED 결제만 PG 환불 → `Payment.cancel()`(COMPLETED→CANCELLED) → `payment.refunded` 발행. 멱등(이미 CANCELLED면 no-op), PG 환불 실패는 throw → DLT.
- `Payment.cancel()`은 COMPLETED 상태에서만 허용(그 외 IllegalStateException). 이미 `COMPLETED`인 결제를 `FAILED`로 변경 금지.
