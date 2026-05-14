# XRail MSA — API 명세서

> 이 문서는 외부(SPA)에서 호출하는 **REST API**와 서비스 간 교환되는 **Kafka 이벤트 계약**을 정의한다. 모든 외부 호출은 `api-gateway`(`http://localhost:8080`)를 경유한다.

## 0. 공통 규약

### 0.1 베이스 URL

| 환경 | 베이스 URL |
|------|-----------|
| 로컬 | `http://localhost:8080` |
| 운영 | (TBD) |

### 0.2 인증 헤더

| 헤더 | 사용 시점 | 형식 |
|------|----------|------|
| `Authorization` | 모든 보호 엔드포인트 | `Bearer <accessToken>` |
| `X-Captcha-Token` | `/api/queue/token`, `/api/auth/signup` 등 봇 방지 필요 | CAPTCHA 공급자 토큰 |
| `Idempotency-Key` | `POST /api/reservations`, `POST /api/payments`, `POST /api/queue/token` | 클라이언트 생성 UUID (36자) |
| `X-Queue-Token` | `POST /api/reservations` | queue-service가 발급한 HMAC. 활성 큐 통과 증거. |

> **Gateway 내부 헤더** (`X-User-Id`, `X-User-Role`, `X-User-Name`, `X-Trace-Id`)는 Gateway가 자동 주입한다. 클라이언트가 이 헤더를 보내도 Gateway가 unconditional로 제거한다(스푸핑 방지).

### 0.3 공통 응답 envelope

모든 응답은 다음 형식.

```json
{
  "success": true,
  "code": "OK",
  "message": "성공",
  "data": { /* 핸들러별 */ },
  "timestamp": "2026-05-14T08:32:11.234Z"
}
```

에러 시:

```json
{
  "success": false,
  "code": "SEAT_ALREADY_TAKEN",
  "message": "이미 예약된 좌석입니다.",
  "data": null,
  "timestamp": "2026-05-14T08:32:11.234Z"
}
```

### 0.4 HTTP 상태 코드 의미

| 코드 | 의미 |
|------|------|
| 200 | OK — 정상 응답 |
| 201 | Created — 리소스 생성 (회원가입, 예약, 결제) |
| 204 | No Content — 삭제·취소 성공 |
| 400 | 입력 검증 실패 (`@Valid`) |
| 401 | 인증 실패 (JWT 누락/만료/위조) |
| 403 | 권한 없음 (role 부족) |
| 404 | 리소스 없음 |
| 409 | 충돌 (좌석 중복, Idempotency-Key 충돌) |
| 410 | Gone — 만료된 큐 토큰, 만료된 PENDING 예약 |
| 422 | 비즈니스 규칙 위반 (station 미포함 등) |
| 429 | 레이트리미트 초과 (Bucket4j) |
| 500 | 서버 내부 오류 |
| 503 | 의존 서비스 장애 (Kafka/Redis/MySQL down) |

### 0.5 표준 에러 코드 (`ErrorCode` enum)

| code | HTTP | 발생 위치 | 의미 |
|------|------|----------|------|
| `USER_NOT_FOUND` | 404 | auth | 회원/비회원 미존재 |
| `UNAUTHORIZED` | 401 | gateway | JWT 무효 |
| `FORBIDDEN` | 403 | gateway/service | role 부족 |
| `SEAT_ALREADY_TAKEN` | 409 | train | Lua 비트마스크 충돌 |
| `LATE_RESERVATION` | 422 | train | 출발 시각 임박/지남 |
| `STATION_NOT_IN_ROUTE` | 422 | train | 노선에 없는 역 |
| `RESERVATION_NOT_FOUND` | 404 | train | 예약 미존재/타인 |
| `RESERVATION_EXPIRED` | 410 | train | PENDING 만료 |
| `PAYMENT_FAILED` | 422 | payment | PG 실패 |
| `IDEMPOTENCY_CONFLICT` | 409 | payment/train/queue | 동일 키로 다른 페이로드 |
| `QUEUE_NOT_ACTIVE` | 403 | train | 큐 토큰 누락/만료 |
| `QUEUE_TOKEN_INVALID` | 401 | train | HMAC 검증 실패 |
| `CAPTCHA_FAILED` | 401 | gateway | CAPTCHA 검증 실패 |
| `RATE_LIMITED` | 429 | gateway | Bucket4j 초과 |
| `INTERNAL_ERROR` | 500 | * | 잡지 못한 예외 |

### 0.6 페이지네이션 규약

```
GET /api/...?page=0&size=20&sort=createdAt,desc
```

응답:

```json
{
  "success": true,
  "data": {
    "content": [ /* items */ ],
    "page": 0,
    "size": 20,
    "totalElements": 153,
    "totalPages": 8
  }
}
```

---

## 1. auth-service API (`/api/auth/**`)

### 1.1 회원가입

**`POST /api/auth/signup`** — 인증 불필요

Request:
```json
{
  "loginId": "alice123",
  "password": "P@ssw0rd!",
  "name": "앨리스",
  "email": "alice@example.com",
  "phone": "01012345678",
  "birthDate": "19900101"
}
```

검증:
- `loginId`: 5~20자, 영문 + 숫자, 중복 불가
- `password`: 8자 이상, 영문/숫자/특수문자 각 1개 이상
- `email`: RFC 형식, 중복 불가

Response 201:
```json
{
  "success": true,
  "code": "OK",
  "data": { "userId": 42, "loginId": "alice123" }
}
```

에러: `IDEMPOTENCY_CONFLICT`(loginId/email 중복), `CAPTCHA_FAILED`, `RATE_LIMITED`

---

### 1.2 로그인

**`POST /api/auth/login`** — 인증 불필요

Request:
```json
{ "loginId": "alice123", "password": "P@ssw0rd!" }
```

Response 200:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "accessExpiresIn": 1800,
    "refreshExpiresIn": 1209600,
    "user": {
      "userId": 42,
      "name": "앨리스",
      "role": "ROLE_MEMBER"
    }
  }
}
```

에러: `USER_NOT_FOUND`(401로 라벨링하여 enumeration 방지), `RATE_LIMITED`

---

### 1.3 토큰 재발급

**`POST /api/auth/reissue`** — 인증 불필요 (refresh 토큰으로 검증)

Request:
```json
{ "refreshToken": "eyJhbGciOi..." }
```

Response 200: 1.2와 동일 (새 access + refresh 쌍, 회전 체인 갱신)

에러: `UNAUTHORIZED`(refresh 무효/만료/이미 회전됨)

---

### 1.4 비회원 가입

**`POST /api/auth/guest/register`** — 인증 불필요

Request:
```json
{
  "name": "김비회원",
  "phone": "01098765432",
  "password": "1234"
}
```

검증: `password` 4~6자 숫자

Response 201:
```json
{
  "success": true,
  "data": {
    "userId": 100,
    "accessCode": "XR8K2P4Q",
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "role": "ROLE_NON_MEMBER"
  }
}
```

> `accessCode`는 재로그인 / 예약 조회용. 비회원은 별도 보관.

---

### 1.5 비회원 로그인

**`POST /api/auth/guest/login`** — 인증 불필요

Request:
```json
{ "accessCode": "XR8K2P4Q", "phone": "01098765432", "password": "1234" }
```

Response 200: 1.2와 동일 (role=ROLE_NON_MEMBER)

에러: `USER_NOT_FOUND`

---

### 1.6 OAuth2 진입

**`GET /oauth2/authorization/{provider}`** — `provider ∈ {kakao, naver}` — 인증 불필요

Browser redirect 흐름. Gateway → auth-service → 공급자 authorize URL. 자세한 시퀀스는 ARCHITECTURE.md §4.3.

콜백 URI: `GET /login/oauth2/code/{provider}` (서버 처리, 클라이언트 호출 없음)

최종 SPA 리다이렉트:
```
http://localhost:5173/oauth/callback?accessToken=<jwt>&refreshToken=<jwt>
```

---

### 1.7 로그아웃

**`POST /api/auth/logout`** — 인증 필요

Request: body 없음. `Authorization` 헤더의 토큰을 폐기.

Response 204

내부 동작: `refresh_tokens.revoked_at = now()` + Redis `DEL rt:{userId}`.

> 옵션: `POST /api/auth/logout?all=true`로 모든 디바이스 무효화 (deferred 결정).

---

## 2. train-service API

### 2.1 노선/역 조회

**`GET /api/stations`** — 인증 불필요

Response 200:
```json
{
  "success": true,
  "data": {
    "stations": [
      { "stationId": 1, "name": "서울" },
      { "stationId": 2, "name": "광명" },
      { "stationId": 3, "name": "천안아산" }
    ],
    "routes": [
      {
        "routeId": 1, "name": "경부선",
        "stations": [
          { "stationId": 1, "name": "서울", "sequence": 0, "cumulativeDistance": 0.0 },
          { "stationId": 2, "name": "광명", "sequence": 1, "cumulativeDistance": 22.0 }
        ]
      }
    ]
  }
}
```

> SPA 시작 시 1회 호출 + 캐시.

---

### 2.2 스케줄 검색

**`GET /api/schedules`** — 인증 필요

Query:
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `departureStationId` | Long | Y | 출발역 |
| `arrivalStationId` | Long | Y | 도착역 |
| `date` | String (YYYY-MM-DD) | Y | 출발일 |
| `trainType` | String | N | `KTX`/`MUGUNGHWA`/... |

Response 200:
```json
{
  "success": true,
  "data": {
    "schedules": [
      {
        "scheduleId": 1024,
        "trainId": 12,
        "trainNumber": "101",
        "trainType": "KTX",
        "routeId": 1,
        "routeName": "경부선",
        "departureDate": "2026-05-20",
        "departureTime": "08:30:00",
        "arrivalTime": "11:15:00",
        "departureStation": { "stationId": 1, "name": "서울" },
        "arrivalStation": { "stationId": 6, "name": "부산" },
        "duration": "2h 45m",
        "estimatedPrice": 59800,
        "availableSeats": 142
      }
    ]
  }
}
```

에러: `STATION_NOT_IN_ROUTE`(출발/도착이 같은 노선에 없음)

---

### 2.3 좌석 가용 조회

**`GET /api/schedules/{scheduleId}/seats`** — 인증 필요

Query:
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `departureStationId` | Long | Y | 구간 시작 |
| `arrivalStationId` | Long | Y | 구간 종료 |

Response 200:
```json
{
  "success": true,
  "data": {
    "scheduleId": 1024,
    "segment": { "startIdx": 0, "endIdx": 5 },
    "carriages": [
      {
        "carriageId": 200, "carriageNumber": 1,
        "seats": [
          { "seatId": 1001, "seatNumber": "1A", "available": true, "price": 59800 },
          { "seatId": 1002, "seatNumber": "1B", "available": false, "price": 59800 }
        ]
      }
    ]
  }
}
```

> `available` 계산: Redis 비트마스크 `sch:{scheduleId}:seat:{seatId}`에서 `[startIdx, endIdx-1]` 비트가 모두 0인지 검사 (Lua read script).

---

### 2.4 예약 생성

**`POST /api/reservations`** — 인증 필요 + 큐 토큰 필요

Headers:
- `Authorization: Bearer ...`
- `X-Queue-Token: <HMAC>`
- `Idempotency-Key: <uuid>`

Request:
```json
{
  "scheduleId": 1024,
  "departureStationId": 1,
  "arrivalStationId": 6,
  "seatIds": [1001, 1002]
}
```

Response 201:
```json
{
  "success": true,
  "data": {
    "reservationId": 555,
    "status": "PENDING",
    "totalPrice": 119600,
    "reservedAt": "2026-05-14T08:32:11.234Z",
    "expiresAt": "2026-05-14T08:52:11.234Z",
    "tickets": [
      { "ticketId": 9001, "seatId": 1001, "seatNumber": "1A", "price": 59800 },
      { "ticketId": 9002, "seatId": 1002, "seatNumber": "1B", "price": 59800 }
    ]
  }
}
```

처리 흐름 (자세히는 ARCHITECTURE.md §6):
1. `QueueTokenInterceptor`가 HMAC 검증 → 실패 시 401 `QUEUE_TOKEN_INVALID`
2. `IdempotencyInterceptor`가 Redis `reservation:idem:{userId}:{key}` SETNX → 충돌 시 기존 응답 반환
3. `@Transactional` 안에서 각 seatId에 대해 `reserve_seat.lua` EVAL → 1개라도 실패 시 부분 rollback + 409 `SEAT_ALREADY_TAKEN`
4. DB `existsOverlap` 더블체크 → 충돌 시 Lua rollback + 409
5. `INSERT reservations(PENDING)` + `INSERT tickets`
6. Kafka emit: `reservation.created`, `seat.locked`

에러:
- 401 `QUEUE_TOKEN_INVALID`, `UNAUTHORIZED`
- 409 `SEAT_ALREADY_TAKEN`, `IDEMPOTENCY_CONFLICT`
- 422 `STATION_NOT_IN_ROUTE`, `LATE_RESERVATION`

---

### 2.5 예약 목록

**`GET /api/reservations`** — 인증 필요

Query: `page`, `size`, `status`(optional: `PENDING|PAID|CANCELLED`)

Response 200:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "reservationId": 555,
        "status": "PAID",
        "totalPrice": 119600,
        "reservedAt": "2026-05-14T08:32:11.234Z",
        "tickets": [ /* 요약 */ ]
      }
    ],
    "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
  }
}
```

> 본인 예약만 반환 (`WHERE user_id = X-User-Id`). 비회원은 `accessCode`로 다른 엔드포인트 사용.

---

### 2.6 예약 상세

**`GET /api/reservations/{reservationId}`** — 인증 필요

Response 200: 예약 + 티켓 + saga 진행 상태 (last_event).

에러: 404 `RESERVATION_NOT_FOUND` (타인 예약 포함)

---

### 2.7 예약 취소

**`DELETE /api/reservations/{reservationId}`** — 인증 필요

처리: status를 `CANCELLED`로 변경 + `rollback_seat.lua` + Kafka `seat.released(reason=USER_CANCELLED)`.

Response 204

에러: 410 `RESERVATION_EXPIRED`(이미 만료/취소된 건), 403 `FORBIDDEN`(타인 예약)

> PAID 상태도 취소 가능하나 환불 정책은 1차에서는 단순 (구현 단계에서 결정).

---

### 2.8 비회원 예약 조회

**`GET /api/reservations/guest?accessCode={code}&phone={phone}`** — 인증 불필요(자체 인증)

Response 200: 본인 예약 리스트.

> auth-service의 verify 엔드포인트 호출 후 train-service가 응답.

---

## 3. queue-service API (`/api/queue/**`)

### 3.1 CAPTCHA 요청 (1차 stub)

**`GET /api/queue/captcha`** — 인증 불필요

Response 200:
```json
{
  "success": true,
  "data": {
    "captchaId": "c_abc123",
    "imageBase64": "data:image/png;base64,..."
  }
}
```

> stub은 항상 같은 코드("0000") 반환. 실 프로바이더 도입은 deferred.

---

### 3.2 큐 토큰 등록 (대기열 진입)

**`POST /api/queue/token`** — 인증 필요

Headers:
- `Authorization: Bearer ...`
- `X-Captcha-Token: <captchaId>:<code>`
- `Idempotency-Key: <uuid>`

Request:
```json
{ "scope": "global" }
```

> `scope` 기본 `"global"`. 차후 `"schedule:1024"`로 트래픽 분산 가능.

Response 200 (대기 중):
```json
{
  "success": true,
  "data": {
    "scope": "global",
    "rank": 423,
    "totalWaiting": 1200,
    "expectedWaitSeconds": 60,
    "status": "WAITING"
  }
}
```

Response 200 (즉시 활성화 — 대기 없음):
```json
{
  "success": true,
  "data": {
    "scope": "global",
    "status": "ACTIVE",
    "queueToken": "HMAC...",
    "expiresAt": "2026-05-14T08:42:11.234Z"
  }
}
```

에러: 401 `CAPTCHA_FAILED`, 429 `RATE_LIMITED`

---

### 3.3 큐 상태 폴링 (Fallback)

**`GET /api/queue/status?scope=global`** — 인증 필요

Response 200: 3.2 응답과 동일 형식 (현 상태 스냅샷).

> SPA는 EventSource(3.4) 우선 → 2회 연속 실패 시 이 엔드포인트로 2초 polling 전환.

---

### 3.4 큐 SSE 구독

**`GET /api/queue/subscribe?scope=global`** — 인증 필요

Headers: `Accept: text/event-stream`

응답: `text/event-stream` 무한 스트림.

이벤트 형식:
```
: heartbeat
event: heartbeat
data: {}

event: rank
data: {"rank": 421, "totalWaiting": 1198, "expectedWaitSeconds": 58}

event: rank
data: {"rank": 320, "totalWaiting": 1098, "expectedWaitSeconds": 44}

event: active
data: {"queueToken": "HMAC...", "expiresAt": "2026-05-14T08:42:11.234Z"}
```

- `event: rank` — 매 3초(스케줄러 tick) 또는 의미 있는 변동 시.
- `event: heartbeat` — 25초마다 (proxy idle timeout 회피).
- `event: active` — 1회 송신 후 서버가 `complete()`로 종료.

클라이언트 처리:
```js
const es = new EventSource('/api/queue/subscribe?scope=global');
es.addEventListener('rank', e => updateRank(JSON.parse(e.data)));
es.addEventListener('active', e => {
  const { queueToken } = JSON.parse(e.data);
  saveToken(queueToken);
  es.close();
  navigate('/seats');
});
es.onerror = () => /* 자동 재연결, 2회 실패 시 polling 전환 */;
```

---

### 3.5 큐 이탈

**`POST /api/queue/leave`** — 인증 필요

Request: body 없음.

Response 204

내부: `ZREM queue:waiting:{scope} {userId}` + Redis `DEL queue:active:{scope}:{userId}`.

---

## 4. payment-service API (`/api/payments/**`)

### 4.1 결제 요청

**`POST /api/payments`** — 인증 필요

Headers:
- `Authorization: Bearer ...`
- `Idempotency-Key: <uuid>` **필수**

Request:
```json
{
  "reservationId": 555,
  "amount": 119600,
  "method": "CARD"
}
```

검증:
- `amount`는 reservation의 `total_price`와 일치해야 함 (변조 방지). 불일치 시 422 `IDEMPOTENCY_CONFLICT`로 처리하여 단서 노출 최소화.

Response 200 (성공):
```json
{
  "success": true,
  "data": {
    "paymentId": 8888,
    "reservationId": 555,
    "status": "COMPLETED",
    "amount": 119600,
    "method": "CARD",
    "providerTxnId": "PG-2026-051408321100",
    "completedAt": "2026-05-14T08:32:13.123Z"
  }
}
```

Response 422 (실패):
```json
{
  "success": false,
  "code": "PAYMENT_FAILED",
  "message": "결제 처리에 실패했습니다.",
  "data": { "paymentId": 8888, "reason": "INSUFFICIENT_FUNDS" }
}
```

Idempotency 동작:
- 동일 `Idempotency-Key` + 동일 페이로드 → 기존 응답 그대로 반환 (200).
- 동일 키 + **다른 페이로드** → 409 `IDEMPOTENCY_CONFLICT`.
- 동일 키 처리 중 재요청 → 409 `IDEMPOTENCY_CONFLICT` (PROCESSING 상태 잠금 충돌).

처리 흐름 (자세히는 ARCHITECTURE.md §6.2):
1. Redisson lock `payment:lock:{key}` 획득 (waitTime=0, leaseTime=60s).
2. Redis `payment:idem:{key}` 상태 조회 → COMPLETED면 기존 응답 반환.
3. `INSERT payments(REQUESTED, @Version=0)` + `payment.requested` emit.
4. Mock PG 호출 (또는 실 PG).
5. 성공: `UPDATE payments SET status=COMPLETED, version=1` + `payment.completed` emit.
   실패: `UPDATE payments SET status=FAILED` + `payment.failed` emit.
6. Redis 상태 갱신 + lock 해제.

---

### 4.2 결제 조회

**`GET /api/payments/{paymentId}`** — 인증 필요

Response 200: 4.1 응답 형식과 동일.

에러: 404 (타인 결제 포함)

---

### 4.3 결제 취소(환불) (Future)

**`POST /api/payments/{paymentId}/cancel`** — 인증 필요

> 1차 범위 외. 인터페이스만 reserve.

---

## 5. notification-service API (`/api/notifications/**`)

### 5.1 내 알림 조회

**`GET /api/notifications`** — 인증 필요

Query: `page`, `size`, `unreadOnly`(default false)

Response 200:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "notificationId": 1234,
        "channel": "INAPP",
        "template": "PAYMENT_COMPLETED",
        "title": "결제 완료",
        "body": "서울→부산 KTX 좌석 2석 결제가 완료되었습니다.",
        "createdAt": "2026-05-14T08:32:14.567Z",
        "readAt": null
      }
    ],
    "page": 0, "size": 20, "totalElements": 5, "totalPages": 1
  }
}
```

---

### 5.2 알림 읽음 처리

**`POST /api/notifications/{id}/read`** — 인증 필요

Response 204

---

## 6. 관리자 API (참고 — 1차 범위 시 미정)

`/api/admin/**` — `role=ROLE_ADMIN` 필수. Gateway에서 role 검증.

- `GET /api/admin/reservations` — 전체 예약
- `GET /api/admin/payments` — 결제 통계
- `GET /api/admin/users` — 사용자 목록
- `GET /api/admin/schedules` — 스케줄 운영
- `POST /api/admin/schedules` — 스케줄 추가

상세 명세는 deferred.

---

## 7. Kafka 이벤트 계약

### 7.1 공통 envelope

모든 이벤트는 Java record로 정의 + JSON 직렬화. 공통 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| `eventId` | String (UUID) | 멱등 처리·중복 차단용 |
| `occurredAt` | String (ISO-8601 Instant) | 이벤트 발생 시각 |
| `traceId` | String | 분산 트레이싱 ID (Brave `b3`) |

Kafka 메시지 헤더:
- `eventId` — payload와 동일 값 (헤더 기반 dedup용)
- `eventType` — `Topics` 상수의 토픽명
- `b3` — 트레이싱 (`brave-instrumentation-kafka-clients` 인터셉터가 자동 주입)

### 7.2 토픽 일람

| 토픽 | 생산자 | 소비자 | 파티션 키 | 파티션 수 | 비고 |
|------|--------|--------|----------|----------|------|
| `user.signed-up` | auth | notification | userId | 1 | welcome 알림 |
| `reservation.created` | train | payment, notification | reservationId | 3 | saga 시작점 |
| `seat.locked` | train | (감사용) | reservationId | 1 | 좌석 잠금 성공 |
| `seat.lock-failed` | train | (감사용) | reservationId | 1 | (예약 실패 보강용; 1차에는 emit 안 함) |
| `payment.requested` | payment | (감사용) | reservationId | 1 | 결제 시작 감사 |
| `payment.completed` | payment | train, notification | reservationId | 3 | 결제 성공 → 좌석 확정 트리거 |
| `payment.completed.DLT` | (kafka error handler) | payment | reservationId | 1 | retry 실패 메시지 |
| `payment.failed` | payment | train, notification | reservationId | 3 | 결제 실패 → 보상 트리거 |
| `seat.confirmed` | train | notification, queue(opt) | reservationId | 1 | 좌석 최종 확정 |
| `seat.released` | train | notification, queue(opt) | reservationId | 3 | 보상(payment_fail/timeout/cancel/reconcile) |
| `notification.dispatched` | notification | (감사용) | userId | 1 | 알림 완료 감사 |

---

### 7.3 이벤트 상세 명세

#### `user.signed-up`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:30:00.000Z",
  "traceId": "abc123def456",
  "userId": 42,
  "userType": "MEMBER",
  "name": "앨리스",
  "email": "alice@example.com"
}
```

소비자 동작 — notification: welcome 템플릿 발송.

---

#### `reservation.created`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:11.234Z",
  "traceId": "abc123",
  "reservationId": 555,
  "userId": 42,
  "userName": "앨리스",
  "scheduleId": 1024,
  "seatIds": [1001, 1002],
  "startStationIdx": 0,
  "endStationIdx": 5,
  "totalPrice": 119600,
  "expiresAt": "2026-05-14T08:52:11.234Z"
}
```

소비자 동작:
- **payment**: (옵션) 사전 `payments(REQUESTED)` 행 생성 — idempotency 키가 다르면 skip.
- **notification**: 예약 생성 알림 (`RESERVATION_CREATED` 템플릿).

---

#### `seat.locked`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:11.234Z",
  "traceId": "abc123",
  "reservationId": 555,
  "scheduleId": 1024,
  "seatIds": [1001, 1002]
}
```

> 1차에서는 감사 로깅 전용. 추후 좌석 맵 라이브 갱신 푸시에 활용 가능.

---

#### `seat.lock-failed`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "...",
  "traceId": "...",
  "reservationId": null,
  "userId": 42,
  "scheduleId": 1024,
  "conflictingSeatIds": [1002],
  "reason": "BITMASK_CONFLICT"
}
```

> 동기 API 응답으로 이미 실패를 전달하므로 1차에는 emit 생략. 향후 분석용으로 활성 가능.

---

#### `payment.requested`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:12.500Z",
  "traceId": "abc123",
  "paymentId": 8888,
  "reservationId": 555,
  "userId": 42,
  "amount": 119600,
  "method": "CARD",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"
}
```

소비자: (감사 전용)

---

#### `payment.completed`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:13.123Z",
  "traceId": "abc123",
  "paymentId": 8888,
  "reservationId": 555,
  "userId": 42,
  "amount": 119600,
  "providerTxnId": "PG-2026-051408321100"
}
```

소비자 동작:
- **train**: Reservation PAID 갱신, Ticket ISSUED 갱신, `seat.confirmed` emit. 이미 PAID이면 no-op (멱등).
- **notification**: `PAYMENT_COMPLETED` 알림.

> retry max 2 / backoff 1s. 누적 3회 실패 시 `payment.completed.DLT`로.

---

#### `payment.failed`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:13.500Z",
  "traceId": "abc123",
  "paymentId": 8888,
  "reservationId": 555,
  "userId": 42,
  "amount": 119600,
  "reason": "INSUFFICIENT_FUNDS"
}
```

소비자 동작:
- **train**: `rollback_seat.lua` 호출 + Reservation CANCELLED + `seat.released(reason=PAYMENT_FAILED)` emit.
- **notification**: `PAYMENT_FAILED` 알림.

---

#### `seat.confirmed`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:13.234Z",
  "traceId": "abc123",
  "reservationId": 555,
  "userId": 42,
  "ticketIds": [9001, 9002]
}
```

소비자 동작:
- **notification**: `SEAT_CONFIRMED` 알림 (티켓 QR/번호 발급 알림).
- **queue (opt)**: active count metrics 감소.

---

#### `seat.released`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:52:11.500Z",
  "traceId": "abc123",
  "reservationId": 555,
  "userId": 42,
  "scheduleId": 1024,
  "seatIds": [1001, 1002],
  "startStationIdx": 0,
  "endStationIdx": 5,
  "reason": "TIMEOUT"
}
```

`reason ∈ { PAYMENT_FAILED, TIMEOUT, USER_CANCELLED, RECONCILE }`.

소비자 동작:
- **notification**: reason별 알림 템플릿 분기.
- **queue (opt)**: 좌석맵 갱신 트리거.

---

#### `notification.dispatched`

```json
{
  "eventId": "01HABCDEF...",
  "occurredAt": "2026-05-14T08:32:14.567Z",
  "traceId": "abc123",
  "notificationId": 1234,
  "userId": 42,
  "channel": "INAPP",
  "template": "PAYMENT_COMPLETED",
  "correlationId": "01HABCDEF..."
}
```

소비자: (감사 전용)

---

### 7.4 Saga 이벤트 시퀀스 매트릭스

이벤트가 발생하는 순서와 보상 경로를 한눈에:

| 단계 | 정상 흐름 | 결제 실패 | 타임아웃 | 사용자 취소 |
|------|----------|----------|---------|------------|
| 1 | `reservation.created` | `reservation.created` | `reservation.created` | `reservation.created` |
| 2 | `seat.locked` | `seat.locked` | `seat.locked` | `seat.locked` |
| 3 | `payment.requested` | `payment.requested` | (미발생) | (미발생) |
| 4 | `payment.completed` | `payment.failed` | (미발생) | (미발생) |
| 5 | `seat.confirmed` | `seat.released(PAYMENT_FAILED)` | `seat.released(TIMEOUT)` | `seat.released(USER_CANCELLED)` |
| 6 | `notification.dispatched`(×N) | `notification.dispatched` | `notification.dispatched` | `notification.dispatched` |

---

### 7.5 컨슈머 멱등 보장

각 컨슈머는 다음 패턴 중 하나로 멱등 처리:

1. **상태 기반**: train-service의 `payment.completed` 컨슈머는 `Reservation.status` 가 이미 `PAID`/`CANCELLED`이면 no-op.
2. **eventId 기반**: notification-service는 `notification_logs.correlation_id`에 unique 인덱스 → 중복 INSERT 시 silently skip.
3. **버전 기반**: payment-service 내부 retry는 `@Version` 충돌 시 재조회 후 분기.

---

### 7.6 DLT 처리 정책

| 토픽 | 재시도 | 백오프 | DLT |
|------|--------|--------|-----|
| `payment.completed` (train consumer) | max 2 | 1s 고정 | `payment.completed.DLT` |
| 그 외 | max 3 | 1s → 2s → 4s exp | `<topic>.DLT` 자동 생성 |

DLT 메시지 구조: 원본 payload + Kafka 헤더 `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`. 운영자는 DLT 컨슘 후 수동 재처리 / 알림 발송.

---

## 8. SSE 사용 가이드 (클라이언트)

### 8.1 구독 흐름

```typescript
function useQueueStatus(scope: string) {
  const [state, setState] = useState<QueueStatus>({status: 'WAITING', rank: -1});
  const failuresRef = useRef(0);

  useEffect(() => {
    const es = new EventSource(
      `${API_BASE}/api/queue/subscribe?scope=${scope}`,
      { withCredentials: true }
    );
    es.addEventListener('rank', e => {
      failuresRef.current = 0;
      setState(JSON.parse(e.data));
    });
    es.addEventListener('active', e => {
      setState({...JSON.parse(e.data), status: 'ACTIVE'});
      es.close();
    });
    es.onerror = () => {
      failuresRef.current += 1;
      if (failuresRef.current >= 2) {
        es.close();
        startPolling();
      }
    };
    return () => es.close();
  }, [scope]);

  function startPolling() {
    const id = setInterval(async () => {
      const r = await fetch(`${API_BASE}/api/queue/status?scope=${scope}`);
      const json = await r.json();
      setState(json.data);
      if (json.data.status === 'ACTIVE') clearInterval(id);
    }, 2000);
  }

  return state;
}
```

### 8.2 헤더 주의

EventSource API는 커스텀 헤더 미지원 → JWT는 다음 중 하나로 전달:
- **쿠키** (`Set-Cookie: AccessToken=...; SameSite=Strict; HttpOnly`).
- **쿼리 파라미터** `?token=...` (보안상 권장 안 함).
- **EventSource Polyfill**(IE/구식 브라우저 + 헤더 주입) — 1차 미지원.

→ 1차 구현에서는 SPA가 로그인 시 쿠키 + 헤더 동시 발급 받고, SSE 호출엔 쿠키 사용.

---

## 9. 레이트리미트 (Bucket4j)

Gateway에서 IP 기반 토큰 버킷 적용.

| 엔드포인트 | 한도 | 비고 |
|-----------|------|------|
| `POST /api/auth/login` | 10/min/IP | brute-force 방지 |
| `POST /api/auth/signup` | 5/min/IP | 계정 양산 방지 |
| `POST /api/queue/token` | 30/min/IP | 큐 등록 |
| `POST /api/reservations` | 60/min/IP | 정상 사용자 보호 |
| `POST /api/payments` | 30/min/IP | 결제 brute-force 방지 |
| 그 외 GET | 600/min/IP | 일반 조회 |

초과 시 429 `RATE_LIMITED` + `Retry-After` 헤더.

> 운영에서는 인증 사용자 단위로도 분리 권장 (`per-user limit`). 1차에서는 IP 기준.

---

## 10. CORS

Gateway 설정:

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins:
              - "http://localhost:5173"
              - "https://xrail.example.com"
            allowedMethods: [GET, POST, PUT, DELETE, OPTIONS]
            allowedHeaders: "*"
            allowCredentials: true
            maxAge: 3600
```

`Authorization`, `Idempotency-Key`, `X-Queue-Token`, `X-Captcha-Token` 등 커스텀 헤더 허용.

---

## 11. Versioning 정책

- URL prefix `/api/v1/...` 미사용 (1차 단순화). v2 도입 시 path 추가.
- Kafka 토픽: `<event>.v1` 명명은 도입 안 함. 호환 깨지면 새 토픽(`reservation.created.v2`)으로 fork + 일정 기간 dual-publish.

---

## 12. 검증 시나리오 (E2E curl 예시)

```bash
BASE=http://localhost:8080

# 1. 회원가입
curl -X POST $BASE/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"loginId":"alice","password":"P@ssw0rd!","name":"앨리스","email":"a@b.com","phone":"01012345678","birthDate":"19900101"}'

# 2. 로그인
TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"loginId":"alice","password":"P@ssw0rd!"}' | jq -r .data.accessToken)

# 3. 스케줄 검색
curl "$BASE/api/schedules?departureStationId=1&arrivalStationId=6&date=2026-05-20" \
  -H "Authorization: Bearer $TOKEN"

# 4. 큐 등록
QT=$(curl -s -X POST $BASE/api/queue/token \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Captcha-Token: c_abc123:0000" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"scope":"global"}' | jq -r .data.queueToken)

# 5. 좌석 가용
curl "$BASE/api/schedules/1024/seats?departureStationId=1&arrivalStationId=6" \
  -H "Authorization: Bearer $TOKEN"

# 6. 예약 생성
RES=$(curl -s -X POST $BASE/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Queue-Token: $QT" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"scheduleId":1024,"departureStationId":1,"arrivalStationId":6,"seatIds":[1001,1002]}')
RID=$(echo $RES | jq .data.reservationId)

# 7. 결제
curl -X POST $BASE/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d "{\"reservationId\":$RID,\"amount\":119600,\"method\":\"CARD\"}"

# 8. 예약 조회 (PAID 확인)
curl "$BASE/api/reservations/$RID" -H "Authorization: Bearer $TOKEN"
```

---

**End of API.md**
