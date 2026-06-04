# notification-service — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 notification-service 작업 시 추가로 지킬 규칙이다.

## 역할

Kafka 이벤트 수신 → 알림 로그 저장 → 채널 전송. Kafka consumer 전용 서비스 (외부 REST 노출 최소).

## 규칙

### N1. 멱등 처리 (핵심)
- 모든 이벤트 처리 시 `notification_logs.correlation_id`(= 이벤트의 `eventId`) + `channel`로 INSERT 시도.
- `uk_notify_correlation` UNIQUE 제약 위반 시 → `DataIntegrityViolationException` catch → **정상 커밋** (이미 처리됨).
- 예외를 throw하면 Kafka가 재시도 → 무한 중복 발송. 멱등 처리를 "선조회 후 INSERT"(check-then-act)로 하지 않는다 — race condition 위험.

### N2. 채널 어댑터 패턴
- 채널(`INAPP`, `SMS`, `EMAIL`, `PUSH`)별 어댑터를 인터페이스(`NotificationChannel`)로 분리.
- 1차에서는 `INAPP` 채널만 실구현. `SMS`, `EMAIL`, `PUSH`는 stub (로그만 출력).
- 새 채널 추가 시 기존 코드 수정 없이 새 어댑터 클래스 추가만으로 가능하도록 설계.

### N3. 템플릿 관리
- 템플릿: `WELCOME`, `RESERVATION_CREATED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `SEAT_RELEASED_TIMEOUT`, `PAYMENT_REFUNDED`(환불 완료). 템플릿은 자유 문자열 컬럼이므로 추가 시 별도 레지스트리 등록 불필요.
- 템플릿 변수는 `payload_json`에서 읽는다. 이벤트 DTO 필드에 직접 접근하지 않고 JSON 파싱.
- 지원하지 않는 이벤트가 들어오면 warn 로그 후 skip. 예외 throw 금지.

### N4. Kafka 토픽 구독
- 구독 토픽: `user.signed-up`, `reservation.created`, `payment.completed`, `payment.failed`, `seat.confirmed`, `seat.released`, `payment.refunded`(→ `PAYMENT_REFUNDED` 알림).
- `seat.released`의 `reason` 필드로 알림 템플릿 분기: `TIMEOUT` → `SEAT_RELEASED_TIMEOUT`, `PAYMENT_FAILED` → `PAYMENT_FAILED`, 나머지 → 로그만.
- `notification.dispatched` 이벤트는 **이 서비스가 발행**. 다른 서비스가 이 이벤트를 소비해서 비즈니스 로직을 실행하면 안 된다 (감사용).

### N5. 비동기 처리
- 채널 전송(`sendToChannel`)은 `@Async`로 비동기 처리. 전송 실패가 Kafka 컨슈머 커밋을 막으면 안 된다.
- 전송 실패 시 `notification_logs.status = FAILED`, `failure_reason` 기록. 재시도는 별도 배치로 (1차 deferred).

### N6. 스키마 격리
- `xrail_notify` 스키마만 접근. `user_id`는 이벤트에서 받은 Long값만 저장.
- 알림 내용에 사용자 상세 정보(이름 등)가 필요하면 이벤트 payload에서 가져온다. auth 서비스 HTTP 호출 금지.
