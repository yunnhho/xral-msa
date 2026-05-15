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

### P4. Kafka 이벤트
- `payment.completed` 발행 후 트랜잭션 커밋 실패 시 이벤트만 발행된 상태가 된다 — Transactional Outbox 패턴 고려 대상 (1차 deferred, 주석으로 TODO 표시).
- `payment.requested`는 감사(audit) 전용. 다른 서비스가 이 이벤트로 비즈니스 로직을 실행하면 안 된다.

### P5. DLT 처리
- `payment.completed` 컨슈머(train-service 쪽)가 실패하면 `payment.completed.DLT`로 격리.
- DLT 메시지 수신 시 운영자에게 알림(log + 향후 Slack). 자동 재처리 로직 내부에 작성 금지.
- DLT 핸들러는 예외를 throw하지 않는다 — DLT에서 또 실패하면 무한 루프.

### P6. 스키마 격리
- `xrail_payment` 스키마만 접근. `reservation_id`는 Long 참조값일 뿐 — train 스키마 조회 금지.
- 결제 금액 검증은 `POST /api/payments` 요청 body의 `amount`와 이벤트/헤더의 값을 비교. train DB 조회로 검증하지 않는다.

### P7. 상태 전이
- `Payment.status` 전이: `REQUESTED → COMPLETED | FAILED`. COMPLETED → CANCELLED는 환불 시 (1차 deferred).
- 이미 `COMPLETED`인 결제를 `FAILED`로 변경 금지. 상태 전이 위반 시 예외 throw.
