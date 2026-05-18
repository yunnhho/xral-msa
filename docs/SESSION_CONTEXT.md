# XRail MSA — 세션 인수인계 컨텍스트

> 이 파일을 다음 세션 시작 시 그대로 붙여넣으면 이전 세션의 맥락을 이어받아 작업할 수 있습니다.
> 최종 갱신: 2026-05-18 (정적 검증 세션)

---

## 현재 상태 요약

**진행: M0 ✅ → M1 ✅ → M2 ✅ → M3 ✅(happy path) → 정적 검증 ✅ → 빌드/런타임 검증 🔲 → M3 잔여 🔲 → M4 🔲 → ...**

PRD.md §11 마일스톤 기준으로 M0~M3 코드 작성 완료. 이번 세션에서 **정적 검증**(import/시그니처/엔티티 필드/Repository 메서드명/이벤트 record 호출자 일치) 수행 → 컴파일 에러 가능성 항목 미발견. 단 Gradle 미설치 + `gradlew.bat` 부재로 실제 `compileJava` 동적 빌드는 미실시. **다음 세션 첫 작업은 Gradle 설치 → wrapper 생성 → 동적 빌드 검증**.

---

## 이번 세션에서 만든 파일 목록

### auth-service (`com.xrail.auth`)
```
src/main/resources/db/migration/
  V1__init_auth.sql          # users(JOINED)/members/non_members/refresh_tokens
  V2__alter_member_password_nullable.sql  # 소셜 전용 회원 password_hash NULL

src/main/java/com/xrail/auth/
  entity/User.java            # abstract, @Inheritance(JOINED), dtype 컬럼
  entity/Member.java          # JOINED 하위, email UK, social_provider/social_id
  entity/NonMember.java       # JOINED 하위, access_code(nanoid 10), password(bcrypt)
  entity/RefreshToken.java    # token_hash=SHA-256, rotated_from 체인
  entity/enums/UserRole.java  # ROLE_MEMBER, ROLE_ADMIN, ROLE_NON_MEMBER
  entity/enums/SocialProvider.java
  repository/MemberRepository.java
  repository/NonMemberRepository.java
  repository/RefreshTokenRepository.java
  security/JwtTokenProvider.java    # HS256, access 30m, refresh 14d
  security/SecurityConfig.java      # permitAll: /api/auth/**, /oauth2/**, /actuator/**
  security/CustomUserDetails.java
  security/CustomUserDetailsService.java
  config/PasswordEncoderConfig.java  # BCrypt strength=12
  config/RedissonConfig.java         # DB 0, rt:{userId} mirror
  oauth2/OAuth2UserInfo.java         # interface
  oauth2/KakaoUserInfo.java
  oauth2/NaverUserInfo.java
  oauth2/CustomOAuth2UserService.java  # Kakao/Naver upsert → Member
  oauth2/CustomOAuth2User.java
  oauth2/OAuth2SuccessHandler.java   # redirect → http://localhost:5173/oauth/callback
  service/RefreshTokenService.java   # SHA-256 hash + Redis mirror + rotation
  service/AuthService.java           # signUp/login/refresh/logout/getMe
  service/NonMemberService.java      # register(nanoid)/login(code+phone+pw)
  dto/SignUpRequest.java, LoginRequest.java, LoginResponse.java
  dto/RefreshRequest.java, MeResponse.java
  dto/NonMemberRegisterRequest.java, NonMemberLoginRequest.java, NonMemberResponse.java
  controller/AuthController.java     # POST /api/auth/{signup,login,refresh,logout}, GET /me
  controller/NonMemberController.java  # POST /api/auth/non-member/{register,login}
  kafka/AuthEventProducer.java       # → user.signed-up (Topics.USER_SIGNED_UP)
  exception/AuthExceptionHandler.java
```

### api-gateway (`com.xrail.gateway`)
```
src/main/java/com/xrail/gateway/
  filter/HeaderStripFilter.java    # Order -200: inbound X-User-* 무조건 제거 (G3)
  filter/JwtValidationFilter.java  # Order -100: Bearer→검증→X-User-* 주입
  filter/CaptchaStubFilter.java    # Order -75: /api/auth/signup + /api/queue/token 보호
  filter/RateLimitFilter.java      # Order -50: Bucket4j per-IP ConcurrentHashMap
  security/JwtVerifier.java        # HS256 verify (공유 JWT_SECRET)
  dto/JwtClaims.java               # record
  exception/GatewayExceptionHandler.java  # ErrorWebExceptionHandler → ApiResponse JSON
```
**참고**: CORS, 라우팅은 `application.yml` globalcors/routes 섹션에 기정의됨. Java Config 불필요.

### train-service (`com.xrail.train`)
```
src/main/resources/
  db/migration/V1__init_train.sql    # stations/routes/route_stations/trains/carriages/seats/schedules
  db/migration/V2__init_reservation.sql  # reservations/tickets/reservation_saga_log
  db/migration/V3__sample_data.sql   # 서울/대전/동대구/부산, 경부선, KTX-001, 내일 08:00 스케줄, 80석
  lua/reserve_seat.lua               # (기존 존재) bitmasking atomic reserve
  lua/rollback_seat.lua              # (기존 존재) bitmasking rollback

src/main/java/com/xrail/train/
  entity/Station.java, Route.java, RouteStation.java
  entity/Train.java, Carriage.java, Seat.java, Schedule.java
  entity/Reservation.java  # user_id/user_name 스냅샷, status PENDING/PAID/CANCELLED
  entity/Ticket.java       # schedule_id/seat_id/start_end_station_id/idx, status RESERVED→ISSUED
  entity/ReservationSagaLog.java  # direction OUTBOUND/INBOUND, observed_at
  entity/enums/TrainType.java, ReservationStatus.java, TicketStatus.java
  repository/ (8개 인터페이스)
  service/LuaScriptService.java      # classpath:lua/*.lua 로드, tryReserve/rollback/isFree
  service/SagaLogService.java        # recordOutbound/recordInbound (REQUIRES_NEW tx)
  service/ReservationService.java    # Lua+DB더블체크+INSERT+Kafka (T2 3중안전망)
  service/ScheduleService.java       # search(routeId, date)
  service/SeatService.java           # getAvailability(scheduleId, startIdx, endIdx)
  dto/ (5개 record/class)
  controller/StationController.java    # GET /api/stations
  controller/ScheduleController.java   # GET /api/schedules?routeId&date
  controller/SeatController.java       # GET /api/schedules/{id}/seats?startStationIdx&endStationIdx
  controller/ReservationController.java  # POST /api/reservations, GET /{id}, GET /me
  kafka/TrainEventProducer.java        # reservation.created/seat.locked/seat.lock-failed/seat.confirmed/seat.released
  kafka/PaymentEventConsumer.java      # payment.completed→PAID+seat.confirmed, payment.failed→CANCELLED+rollback
  scheduler/ReservationExpiryScheduler.java  # @Scheduled(fixedDelay=60s): PENDING만료→CANCELLED+seat.released(TIMEOUT)
  config/RedissonConfig.java           # DB 1
  exception/TrainExceptionHandler.java
```

---

## 스키마 핵심 사항 (엔티티 작성 시 참고)

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
PaymentCompletedEvent(eventId, occurredAt, traceId, paymentId, reservationId, userId, amount, providerTxnId)
PaymentFailedEvent(eventId, occurredAt, traceId, paymentId, reservationId, userId, amount, reason)
```

---

## 다음에 해야 할 작업 (우선순위 순)

### 1. 환경 준비 + 동적 빌드 검증 (최우선)

**환경 제약 발견 (이번 세션)**: Windows 환경에 `gradle` 명령어 없음, `gradlew.bat` 부재. PowerShell `Get-Command gradle` 결과 없음.

준비 단계:
```powershell
# Gradle 설치 (택 1)
scoop install gradle
# 또는
choco install gradle

# wrapper 생성 (xrail-msa 루트에서)
gradle wrapper --gradle-version 8.10
```

그 후 빌드 검증:
```powershell
.\gradlew.bat :common-lib:compileJava
.\gradlew.bat :auth-service:compileJava
.\gradlew.bat :api-gateway:compileJava
.\gradlew.bat :train-service:compileJava
```

**정적 검증은 통과한 상태** — 동적 빌드에서 새로 드러나는 에러는 거의 확률적으로 다음 범주: ① 추가 의존성 누락(annotationProcessor 등) ② Lombok 어노테이션 처리 ③ Bucket4j/JJWT 라이브러리 미세 버전 충돌. 컴파일 통과 후 ReconciliationScheduler(§2)로 진행.

### 2. M3 잔여 — ReconciliationScheduler
**파일**: `train-service/.../scheduler/ReconciliationScheduler.java`
- `@Scheduled(fixedDelay=300_000)` (5분)
- DB에서 PENDING/PAID reservation의 tickets 조회 → 각 seatId별 bitmap 확인
- bitmap에는 set되어 있지만 DB ticket이 없는 경우 → rollback + log
- **DB가 진실(source of truth)**: bitmap을 DB에 맞게 교정 (T4)

### 3. M4 — queue-service
**위치**: `queue-service/src/main/java/com/xrail/queue/`
**핵심 구현**:
- `QueueService`: Redis Sorted Set `queue:waiting:{scope}` (score=timestamp), 3초마다 top-100 promote → `queue:active:{scope}:{userId}` (TTL 10분)
- `QueueTokenService`: HMAC-SHA256(`${QUEUE_HMAC_SECRET}`, `userId:scope:issuedAt`) → Base64 토큰
- `QueueController`: POST /api/queue/enter, GET /api/queue/status, GET /api/queue/token, DELETE /api/queue/leave
- `QueueScheduler`: `@Scheduled(fixedDelay=3000)` — top-100 promote + SSE push
- SSE: `SseEmitter` per userId, `/api/queue/subscribe` (response-timeout:0 in gateway yml 기정의)
- Redis DB 2

### 4. M5 — payment-service
**위치**: `payment-service/src/main/java/com/xrail/payment/`
**핵심 구현**:
- Flyway: `V1__init_payment.sql` → payments(id, reservation_id, user_id, amount, status, idempotency_key UK, version, created_at, updated_at)
- `Payment` entity: `@Version` 필드 필수 (낙관적 잠금)
- Redis idempotency: `payment:idem:{key}` = PROCESSING → COMPLETED (Redisson DB 3)
- Mock PG: 90% 성공 / 10% 실패 랜덤
- Kafka consume: `payment.requested` → 결제 처리 → emit `payment.completed` or `payment.failed`
- DLT: `@DltHandler` or `DeadLetterPublishingRecoverer` (max 2 retry, 1s backoff) — 없으면 무한 재시도

**참고**: payment-service `application.yml`에 Redis DB=3 기정의됨.

### 5. M6 — notification-service
**위치**: `notification-service/src/main/java/com/xrail/notification/`
**핵심 구현**:
- Flyway: notification_logs(id, user_id, channel, template_type, correlation_id UK, status, payload_json, created_at)
- Consumer 6개: user.signed-up, reservation.created, payment.completed, payment.failed, seat.confirmed, seat.released
- correlation_id = `eventId` (UK constraint으로 멱등 보장)
- 템플릿 5개: WELCOME, RESERVATION_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED, SEAT_RELEASED_TIMEOUT

---

## 주의사항 / 알려진 미완성 부분

1. **train-service QueueTokenInterceptor 미구현**: M3 CLAUDE.md T2에서 요구하지만 M4(queue-service) 구현 후 추가 예정. 현재 `@RequestHeader(QUEUE_TOKEN, required=false)`로 우회.

2. **train-service IdempotencyInterceptor 미구현**: 현재 `ReservationService.create()`에서 직접 DB 조회로 처리 중. 전용 인터셉터로 분리 가능하나 기능 동일.

3. **auth-service `AuthController.logout()`**: `@RequestHeader(Headers.USER_ID) Long userId` 사용 — Gateway 통과 후에만 작동. 직접 호출 시 헤더 없어 400 에러. 테스트 시 Gateway 경유 필요.

4. **Gateway `RateLimitFilter`**: 인메모리 ConcurrentHashMap 사용. 서버 재시작 시 버킷 초기화. 분산 환경에서는 Redis bucket으로 교체 필요 (bucket4j-redis 의존성 이미 있음).

5. **Bucket4j API**: `Bandwidth.builder().capacity(N).refillIntervally(N, Duration.ofMinutes(1)).build()` 체인은 Bucket4j 8.10.1에서 valid함을 정적 검증으로 확인. 동적 빌드에서 만약 deprecated 경고가 나면 `Bandwidth.classic(capacity, Refill.intervally(capacity, Duration.ofMinutes(1)))` 로도 교체 가능 (둘 다 8.x 지원).

6. **`ReservationCreatedEvent.scheduleId`**: `tickets.get(0).getScheduleId()`로 첫 티켓의 scheduleId를 사용. 단일 스케줄 가정.

7. **`ScheduleRepository.findByDepartureDateAndRouteRouteId`**: 메서드명 쿼리 — `@EntityGraph(attributePaths = {"route", "train"})` 포함. JPA가 `route.routeId`로 traversal 해석함을 정적 검증에서 확인 (코드는 OK). 실제 런타임에서 sample data로 동작 확인은 별도 필요.

8. **api-gateway `JwtClaims` record 미사용**: `JwtValidationFilter`가 `JwtVerifier`의 반환 `Claims`(JJWT)를 직접 사용하고 자체 record `JwtClaims` 는 인스턴스화되지 않음. 컴파일 에러 아님 — 다음 정리 시점에 record 삭제 또는 어댑터로 활용 결정.

9. **Gradle 환경 부재**: 2026-05-18 세션 시점에 Windows 호스트에 Gradle 미설치 + `gradlew.bat` 없음. 다음 세션 첫 작업으로 §"다음에 해야 할 작업 §1" 절차 수행.

---

## 빠른 로컬 기동 순서

```bash
# 1. .env 파일 준비
cp .env.example .env
# .env 편집: JWT_SECRET (32자 이상), AUTH_DB_PASSWORD, TRAIN_DB_PASSWORD 등

# 2. 인프라 기동
docker-compose up -d mysql redis zookeeper kafka prometheus grafana zipkin

# 3. Eureka 기동
./gradlew :discovery-server:bootRun

# 4. 서비스 순차 기동 (각 별도 터미널)
./gradlew :auth-service:bootRun
./gradlew :api-gateway:bootRun
./gradlew :train-service:bootRun

# 5. 검증
curl http://localhost:8761  # Eureka 대시보드
curl -X POST localhost:8081/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@test.com","password":"Test1234!","name":"테스터"}'
```

---

## 관련 문서
- `docs/PRD.md` §11 — 마일스톤 테이블 (M0~M3 ✅ 표기됨)
- `docs/ARCHITECTURE.md` — 서비스 토폴로지, Kafka 흐름
- `docs/ERD.md` — 스키마 상세, Redis 키 패턴
- `docs/API.md` — REST/Kafka 계약
- `CLAUDE.md` (루트) — 아키텍처 원칙 P1~P8
- `auth-service/CLAUDE.md` — A1~A6 (JWT, 소셜, 비회원 규칙)
- `train-service/CLAUDE.md` — T1~T7 (Lua, Saga, 스케줄러 규칙)
- `api-gateway/CLAUDE.md` — G1~G7 (필터, WebFlux 주의사항)
