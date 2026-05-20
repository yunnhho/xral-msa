# XRail MSA — 세션 인수인계 컨텍스트

> 이 파일을 다음 세션 시작 시 그대로 붙여넣으면 이전 세션의 맥락을 이어받아 작업할 수 있습니다.
> 최종 갱신: 2026-05-19 (빌드 검증, QueueTokenInterceptor, M7 세션)

---

## 현재 상태 요약

**진행: M0 ✅ → M1 ✅ → M2 ✅ → M3 ✅ → M4 ✅ → M5 ✅ → M6 ✅ → 빌드검증 ✅ → QueueTokenInterceptor ✅ → M7 ✅ → M8 ✅ → M9 ✅**

이번 세션에서:
1. **빌드 검증** — Gradle 8.10 wrapper 생성, 의존성 오류 4건 수정(invalid brave 좌표, Redisson `setIfAbsent` API, `java-library` 플러그인 누락, QueryDSL `QBaseTimeEntity` 생성 누락). 전체 7개 서비스 컴파일 성공.
2. **QueueTokenInterceptor** — `train-service`에 HMAC-SHA256 큐 토큰 검증 인터셉터 구현 및 `WebMvcConfig` 등록. `POST /api/reservations`에 적용.
3. **M7** — Kafka Brave tracing 의존성 정비, 모든 서비스 Zipkin docker endpoint 설정, Gateway 5개 서킷브레이커 + fallback, Grafana 대시보드 5패널 JSON.
4. **M8** — React 19 SPA 구현. axios 인터셉터(토큰 자동 주입 + 401 refresh 재시도), AuthContext(메모리 accessToken + localStorage refreshToken), useQueueStatus hook(SSE → 2회 실패 시 polling fallback), 8개 페이지(Login/Signup/GuestLogin/OAuthCallback/Home/Queue/Seat/Payment/Reservations), React Router v7 full routing. TypeScript 컴파일 오류 0건.
5. **M9** — JMeter 5.x 테스트 플랜(`docs/jmeter/xrail-load-test.jmx`). 1,000 users / ramp-up 60s / 1 loop. 시나리오: signup → schedule search → queue(polling fallback 포함) → seat → reserve → pay. 실행 가이드 `docs/jmeter/README.md`.

---

## 이번 세션에서 만든/수정한 파일

### 빌드 수정
```
common-lib/build.gradle       — java-library 플러그인 + QueryDSL annotationProcessor 추가
auth-service/build.gradle     — invalid brave 제거, brave-instrumentation-kafka-clients 추가
train-service/build.gradle    — io.github.openfeign → io.zipkin.brave 수정
payment-service/build.gradle  — 동일
notification-service/build.gradle — 동일
gradlew, gradlew.bat, gradle/wrapper/ — 신규 생성 (Gradle 8.10)
```

### QueueTokenInterceptor (train-service)
```
interceptor/QueueTokenInterceptor.java  — HMAC-SHA256 검증, TTL 체크
config/WebMvcConfig.java               — POST /api/reservations에 인터셉터 등록
src/main/resources/application.yml     — queue.active-ttl-seconds: 600 추가
```

### M7 관측성 + Resilience4j
```
api-gateway/src/main/java/.../filter/FallbackController.java  — 5개 서비스 fallback 엔드포인트
api-gateway/src/main/resources/application.yml                — 서킷브레이커 5개 + timelimiter 설정, Zipkin endpoint
auth-service/src/main/resources/application.yml               — Zipkin endpoint (docker profile)
train-service/.../application.yml                             — 동일
queue-service/.../application.yml                             — 동일
payment-service/.../application.yml                           — 동일
notification-service/.../application.yml                      — 동일
docker/grafana/provisioning/dashboards/xrail-dashboard.json   — 5패널 대시보드 신규
```

---

## 스키마 핵심 사항 (기존 내용 유지)

### auth-service 스키마 특이사항
- `users.user_id` (PK, `@Column(name="user_id")`) — JPA 기본 `id` 아님
- `users.dtype` — JPA `@DiscriminatorColumn(name="dtype")`
- `users.role` 값: `ROLE_MEMBER`, `ROLE_ADMIN`, `ROLE_NON_MEMBER`
- `members.password_hash` — V2 마이그레이션으로 NULL 허용 (소셜 전용 회원)
- `refresh_tokens.rotated_from` — `replaced_by` 아님 주의
- `non_members.password` — `access_password_hash` 아님 주의

### train-service 스키마 특이사항
- `reservations.expires_at = reserved_at + 20분` (5분 아님)
- `reservations` 테이블에 `schedule_id` 없음 — schedule 정보는 `tickets` 테이블에
- `reservation_saga_log.direction` = `OUTBOUND` / `INBOUND` (OUT/IN 아님)
- `reservation_saga_log.observed_at` (created_at 아님)
- `Carriage`, `Seat` 엔티티는 `BaseTimeEntity` 미상속 (SQL에 created_at/updated_at 없음)
- Lua 키 패턴: `sch:{scheduleId}:seat:{seatId}` (T1, 변경 불가)

---

## 공통 라이브러리 이벤트 시그니처 (common-lib)

```java
// 필드 순서 중요 — 변경 금지 (L2)
UserSignedUpEvent(eventId, occurredAt, traceId, userId, userType, name, email)
ReservationCreatedEvent(eventId, occurredAt, traceId, reservationId, userId, userName, scheduleId, seatIds, startStationIdx, endStationIdx, totalPrice, expiresAt)
SeatLockedEvent(eventId, occurredAt, traceId, reservationId, scheduleId, seatIds)
SeatLockFailedEvent(eventId, occurredAt, traceId, reservationId, userId, scheduleId, conflictingSeatIds, reason)
SeatConfirmedEvent(eventId, occurredAt, traceId, reservationId, userId, ticketIds)
SeatReleasedEvent(eventId, occurredAt, traceId, reservationId, userId, scheduleId, seatIds, startStationIdx, endStationIdx, reason)
PaymentRequestedEvent(eventId, occurredAt, traceId, paymentId, reservationId, userId, amount, method, idempotencyKey)
PaymentCompletedEvent(eventId, occurredAt, traceId, paymentId, reservationId, userId, amount, providerTxnId)
PaymentFailedEvent(eventId, occurredAt, traceId, paymentId, reservationId, userId, amount, reason)
NotificationDispatchedEvent(eventId, occurredAt, traceId, notificationId, userId, channel, template, correlationId)
```

---

## 다음에 해야 할 작업 (우선순위 순)

모든 마일스톤(M0~M9) 완료. 추가 작업 없음.

---

## 주의사항 / 알려진 미완성 부분

1. **train-service IdempotencyInterceptor 미구현**: `ReservationService.create()`에서 직접 DB 조회로 처리 중.

2. **auth-service `AuthController.logout()`**: Gateway 통과 후에만 작동. 직접 호출 시 400.

3. **Gateway `RateLimitFilter`**: 인메모리 ConcurrentHashMap. 서버 재시작 시 버킷 초기화.

4. **payment-service `@Transactional` 범위**: `pay()` 전체가 트랜잭션. Redisson lock 획득이 트랜잭션 내부. 허용 범위 (lock < 1초).

5. **queue-service SSE 분산 미지원**: 단일 인스턴스 가정. 수평 확장 시 Redis pub/sub 필요.

6. **notification-service `sendAsync`**: `@Async`로 채널 전송. `NotificationLog` save 중복 가능성 — N1 멱등으로 방어.

7. **`ReservationCreatedEvent.scheduleId`**: `tickets.get(0).getScheduleId()`로 첫 티켓 사용. 단일 스케줄 가정.

8. **Resilience4j `queue-service-cb` timelimiter**: `timeoutDuration: 0s`로 SSE 경로 무제한. Spring Cloud Gateway timelimiter에서 `0s`가 "무제한"으로 동작하지 않을 수 있음 — 운영 테스트 필요.

9. **Grafana datasource UID**: 대시보드 JSON의 datasource uid가 `"prometheus"`. Grafana 인스턴스의 실제 uid와 다를 경우 Grafana UI에서 수동 연결 필요.

---

## 빠른 로컬 기동 순서

```bash
# 1. .env 파일 준비
cp .env.example .env
# .env 편집: JWT_SECRET (32자 이상), QUEUE_HMAC_SECRET, AUTH_DB_PASSWORD, TRAIN_DB_PASSWORD, PAYMENT_DB_PASSWORD, NOTIFY_DB_PASSWORD

# 2. 인프라 기동
docker-compose up -d mysql redis zookeeper kafka prometheus grafana zipkin

# 3. Eureka 기동
./gradlew :discovery-server:bootRun

# 4. 서비스 순차 기동 (각 별도 터미널)
./gradlew :auth-service:bootRun
./gradlew :api-gateway:bootRun
./gradlew :train-service:bootRun
./gradlew :queue-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :notification-service:bootRun

# 5. 검증
curl http://localhost:8761  # Eureka
curl -X POST localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"loginId":"test","password":"Test1234!","name":"테스터","email":"test@test.com","phone":"01012345678","birthDate":"19900101"}'
```

---

## 관련 문서
- `docs/PRD.md` §11 — 마일스톤 테이블
- `docs/ARCHITECTURE.md` — 서비스 토폴로지, Kafka 흐름
- `docs/ERD.md` — 스키마 상세, Redis 키 패턴
- `docs/API.md` — REST/Kafka 계약
- `CLAUDE.md` (루트) — 아키텍처 원칙 P1~P8
- `auth-service/CLAUDE.md` — A1~A6
- `train-service/CLAUDE.md` — T1~T7
- `api-gateway/CLAUDE.md` — G1~G7
- `queue-service/CLAUDE.md` — Q1~Q7
- `payment-service/CLAUDE.md` — P1~P7
- `notification-service/CLAUDE.md` — N1~N6
