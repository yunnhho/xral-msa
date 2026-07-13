# 대기열 진입 제어 재설계 — Rate 방식 → Concurrency(자리 기반) 방식(A)

> 상태: 설계(구현 전) · 대상 서비스: `queue-service`(주), `train-service`(부), `common-lib`
> 관련 문서: [ARCHITECTURE.md](./ARCHITECTURE.md) §5 · [ERD.md](./ERD.md) §5.3 · [problem.md](./problem.md) 성과3 · queue-service/CLAUDE.md Q2
> 목적: 이 문서는 "무엇을 왜 바꾸는지 + 바꿀 때 다른 지점에서 터지는 연쇄 결함"을 미리 확정해 매끄러운 구현을 돕는다.
> 개정(2026-07-13): 코드 대조 리뷰 반영 — INV-2 좌석조회 구멍(§4.5), 재발급 무한 연장(§4.6), leave 토큰 생존(5.16), ABA 클록 skew 마진(§4.2), scope 이중 반환(§4.2), 상호참조 번호 정리.
> 개정 2차(2026-07-13): 결함 재검 반영 — 반환 경로 원자화(release.lua, §4.2), 버킷 부재 시 skip(§4.2), first-키 덮어쓰기 시맨틱·leave 정리(§4.6), 토큰 issuedAt=Lua now 정렬(§4.1), scope 허용목록 검증(표 5.7), 멱등 재시도 토큰 소비(표 5.17), 즉시입장 Lua 만료분 정리·재발급 용량체크 우회(§4.4).

---

## 1. 배경 — 현재 방식(as-is)의 결함

### 1.1 현재 동작

- `QueueScheduler.tick()`(fixedDelay 3s) → `QueueService.promoteTopN(scope, 100)` (`QueueService.java:109`).
- `promoteTopN`은 **대기열 top 100을 무조건 승급**한다. 현재 active 인원도, 이전 배치의 예약 성공/실패도 보지 않는다.
- 승급자는 `queue:active:{scope}:{userId}` 버킷(TTL 600s) + `queue:active:set:{scope}` ZSet(score=만료 epoch)을 갖고, 토큰(HMAC, exp 600s)으로 train-service의 예약/좌석 조회를 통과한다.
- **슬롯 반환 경로가 없다.** 예약 성공/실패/이탈과 무관하게 슬롯은 오직 600s TTL 만료로만 사라진다.

### 1.2 결함 (요약)

`maxActive`(100)를 체크하는 유일한 지점은 `enter()`의 즉시입장 fast-path(`QueueService.java:60`)뿐인데, 이 경로는 `getWaitingSize == 0`일 때만 동작한다. 즉 **대기열이 생기는 폭증 상황에서는 maxActive가 무력화**되고, 시스템은 "동시 100명 상한"이 아니라 "유입 속도 100명/3초"만 강제한다.

> Little's Law: `동시 인원 L = 유입률 λ × 체류시간 W`. 현재 λ≈33/s, W=600s → **L ≈ 20,000**. 커넥션 풀(로컬 30 / prod 100, ARCHITECTURE.md §11)을 보호한다는 목적(problem.md 성과3)과 어긋난다.

### 1.3 업계 표준(참고)

Cloudflare Waiting Room / NetFunnel / AWS Virtual Waiting Room은 모두 **동시 인원 상한(concurrency cap)을 1차 제어 변수**로 두고, 사용자가 보호 구역을 **떠날 때 슬롯을 반환**해 "빈 자리만큼" 다음 사람을 넣는다(자리 기반 리필). 세션시간(TTL)은 no-show 회수를 위한 **보조 안전망**이다. 본 재설계(A)는 이 모델을 따른다.

---

## 2. 목표와 불변식(invariants)

구현·리뷰·테스트가 전부 아래 불변식을 기준으로 판단한다.

- **INV-1 (동시성 상한):** 임의 시점에 `queue:active:set:{scope}`의 유효 원소 수 ≤ `maxActive`. (운영자 `FORCE_OFF` 예외 — 표 5.9. leave 남용 예외 — 표 5.16, 정직한 클라이언트 가정)
- **INV-2 (슬롯 = DB 접근권):** active 슬롯을 가진 사용자만 보호 구역(train-service 예약 생성·좌석 조회)에 접근할 수 있고, 슬롯이 없으면 접근할 수 없어야 한다. 즉 **"슬롯 반환 ⇒ 토큰 사용 불가"가 성립**해야 한다. ⚠ 현재 코드로는 좌석 조회 경로에서 이 등식이 깨진다 — **§4.5의 train-service 수정 1건이 INV-2 성립의 전제**다.
- **INV-3 (진행 보장):** 승급 경로가 살아있는 한, 슬롯이 비면 반드시 다음 대기자가 채워진다(기아 없음). ⚠ `enter()` 재발급 경로가 슬롯을 무한 연장하면 깨진다 — **§4.6의 세션 절대 상한이 전제**다.
- **INV-4 (멱등/순서 안전):** 슬롯 반환 신호는 at-least-once·순서 뒤섞임에도 안전(중복 반환 no-op, 지연 반환이 신규 세션을 침범하지 않음).

> **INV-2가 이 설계의 핵심 난제**다. 토큰은 stateless HMAC이라 train-service가 서명+발급시각(age)만으로 검증한다(`QueueTokenInterceptor.java:106`). queue-service가 슬롯을 지워도 토큰 자체는 죽지 않는다. 따라서 **"슬롯을 반환하는 순간 = 토큰이 더 이상 통하지 않는 순간"인 트리거만 골라야 한다.** (§4.2, §4.5 참조)

---

## 3. 핵심 통찰 — 보호 구역의 경계와 슬롯 생명주기

"보호 구역"을 정확히 그으면 나머지 설계가 자동으로 따라온다.

- 큐 토큰이 실제로 게이팅하는 것은 **`POST /api/reservations`(1회 소비) + `GET .../seats`(검증만)** 두 엔드포인트뿐이다(`QueueTokenInterceptor.java:44-46`).
- **결제는 게이팅되지 않는다.** 예약 PENDING 후 20분 결제 창(`EXPIRES_MINUTES=20`)은 보호 구역 밖이다.
- 따라서 사용자가 보호 구역을 "떠나는" 시점은 곧 **예약 생성이 성공한 시점**이다. 그 뒤 사용자는 큐-게이팅 엔드포인트를 다시 건드리지 않는다.

**결정적 사실:** `reservation.created` 이벤트는 **예약 성공 시에만** 발행된다(`ReservationService.doCreate` → `publishReservationCreated`, `ReservationService.java:153`). 좌석 충돌(409)로 실패한 재시도에서는 발행되지 않고, 이때 토큰은 `used` 처리되지 않아(`afterCompletion`은 2xx에서만 `used` 세팅, `QueueTokenInterceptor.java:100`) 사용자는 슬롯을 정당하게 계속 보유한다.

→ **`reservation.created` = "토큰 소비 완료 = 보호 구역 이탈"을 정확히 만족하는 유일한 조기 반환 신호.**

> ⚠ **단, "이 시점 이후 토큰은 used라 재사용 불가"는 현재 예약 생성(POST) 경로에만 참이다.** used-토큰 차단은 POST에만 적용되고 좌석 조회(GET)는 used 체크 *앞에서* 통과한다(`QueueTokenInterceptor.java:74`). 슬롯 반환 후에도 소비된 토큰으로 좌석 조회가 HMAC 만료까지 가능 → INV-2의 절반이 깨진다(표 5.14). **§4.5에서 좌석 조회에도 used 체크를 적용해 등식을 완성한다.**

### 슬롯 생명주기(to-be)

```
승급(promote) ──▶ ACTIVE ──┬── reservation.created 수신  → 슬롯 반환(정상, 조기)
                           ├── POST /leave (자발 이탈)   → 슬롯 반환
                           └── TTL 만료 (no-show/미완료)  → 슬롯 반환(폴백)
```

- `reservation.created` 경로: 토큰이 이미 used(§4.5 적용 후 좌석 조회 포함) → INV-2 유지.
- TTL 만료 경로: 슬롯 TTL = 토큰 exp라 동시 만료 → INV-2 유지.
- **`POST /leave` 경로는 "정직한 클라이언트 가정"에서만 INV-2를 지킨다.** leave는 자리만 반환하고 stateless 토큰은 죽일 수 없으므로, 악의적 클라이언트는 enter→leave→(살아있는 토큰으로) 예약을 반복해 상한을 우회할 수 있다(표 5.16). used-키는 train Redis(DB 1) 소유라 queue-service가 지울 수도 없다(P5). phase-1은 수용 리스크로 문서화하고 메트릭으로 감시한다.
- 단축 TTL·하트비트 기반 반환은 INV-2를 깨므로 채택하지 않는다(표 5.4 근거).

---

## 4. To-be 설계(A)

### 4.1 Capacity-aware 승급

`promoteTopN`을 "top N 무조건"에서 **"빈 자리만큼만"**으로 변경.

```
capacity = maxActive - getActiveCount(scope)   // 음수면 0
n        = min(batchSize, capacity)            // 이번 tick 승급 인원
if n <= 0: return []                           // 자리 없음 → 이번 tick 승급 0
promote top n from waiting
```

- `batchSize`는 "빈 자리가 많을 때 한 tick에 넣는 상한"으로 의미가 바뀐다(급격한 유입 방지, thundering-herd 완화). 두 knob(`maxActive`, `batchSize`)은 유지.
- **원자성:** check(getActiveCount) → act(promote)가 분리되면 경쟁이 생긴다(표 5.6). queue-service/CLAUDE.md **Q2가 이미 "승급은 Lua atomic 권장"**이라 명시. 승급을 단일 Lua 스크립트로 구현한다:
  - KEYS: `queue:waiting:{scope}`, `queue:active:set:{scope}`
  - ARGV: `maxActive`, `batchSize`, `now`, `expiresAt`(=now+ttl)
  - 로직: `ZCARD active - (만료분 ZREMRANGEBYSCORE 0 now)` 로 현재 active 계산 → `n=min(batch, max-active)` → `ZRANGE waiting 0 n-1` → 각 uid를 active에 ZADD(score=expiresAt) + waiting에서 ZREM → 승급된 uid 목록 반환.
  - 토큰 발급(HMAC)·`queue:active:{scope}:{uid}` 버킷 SET은 Lua 밖(Java)에서 반환된 uid로 수행한다. **이때 토큰 `issuedAt`은 Java에서 새로 찍지 말고 Lua에 넘긴 ARGV `now`를 그대로 쓰고, 버킷 TTL도 `expiresAt - 현재시각`(잔여 시간)으로 설정한다.** Java에서 새 시각을 찍으면 토큰 exp가 zset score(`expiresAt`)보다 늦게 끝나 "슬롯은 만료됐는데 토큰은 유효한" 미세 창이 생긴다(INV-2, 수 ms지만 공짜로 없앨 수 있는 구멍).
- **크래시 창(수용):** Lua의 active-set ZADD(슬롯 점유)와 Java의 버킷 SET 사이에 프로세스가 죽으면 토큰 없는 "유령 슬롯"이 남는다. 이 슬롯은 TTL(score=expiresAt)로 자연 회수되고 그동안 capacity만 그만큼 줄어들 뿐 INV를 깨지 않는다 — 별도 복구 로직을 만들지 않는다(수용 리스크).

### 4.2 슬롯 반환 — `reservation.created` 소비

queue-service에 **첫 Kafka 컨슈머**를 추가(현재 컨슈머 없음, spring-kafka 의존성·컨슈머 yml 설정은 이미 존재).

```
@KafkaListener(topics = Topics.RESERVATION_CREATED, groupId = "queue-service")
onReservationCreated(ReservationCreatedEvent e):
    releaseSlot("global", e.userId(), e.occurredAt())                      // phase-1 기본 scope
    releaseSlot("schedule:" + e.scheduleId(), e.userId(), e.occurredAt())  // scope 확장 대비 방어적 이중 반환
```

> **scope 이중 반환 이유(표 5.7):** phase-1은 `enter()` scope 허용목록 검증(표 5.7 ③)으로 `global`만 허용되지만, 검증이 해제(scope 확장)될 때 컨슈머를 함께 고치는 것을 잊는 배포 실수를 방어한다. 반환이 멱등(없는 원소 ZREM = no-op)이므로 두 후보 scope 모두 반환을 시도하는 것이 싸고 안전하다.

`releaseSlot(scope, uid, occurredAt)` — **가드 판정과 삭제를 단일 Lua 스크립트(`release.lua`)로 원자 실행한다.** 가드(버킷 GET→issuedAt 파싱→비교)와 ZREM/DEL을 Java에서 분리하면, 그 사이에 `enter()` 재발급이 끼어들어 방금 갱신된 새 세션을 지우는 TOCTOU가 생긴다 — §5.6에서 지적한 check-then-act를 반환 경로에 재현하는 꼴이므로 금지.
1. **버킷 부재 → skip (반환하지 않음):** 버킷이 없으면 issuedAt을 판정할 수 없다. 특히 §4.1 승급 창(Lua ZADD 후 Java 버킷 SET 전)에 지연 반환이 끼면, ZREM 시 "토큰·버킷은 곧 생기는데 자리 카운트만 비는" 상태가 되어 다음 tick이 1명 초과 승급한다. skip해도 잔존 zset 원소는 score(=expiresAt) 기반 정리로 자연 회수된다.
2. **ABA 가드(INV-4) + 클록 skew 마진:** 버킷 값(토큰) 끝의 `issuedAt`을 파싱. **`issuedAt < occurredAt - skewMargin`(기본 2s)일 때만 반환**하고, 그 외에는 skip한다.
   - `issuedAt > occurredAt`: 이벤트가 소비한 토큰보다 나중에 발급 → 사용자가 이미 재진입/재발급 → 반환하면 신규 세션 침범(ABA). skip.
   - 마진이 필요한 이유: `issuedAt`은 queue-service 클록, `occurredAt`은 train-service 클록(`TrainEventProducer.java:34`)이라 **서로 다른 시계**다. "예약 직후 재진입" 창이 수 초라 클록 skew(수백 ms~수 초)가 오판을 만들 수 있다. 오판의 두 방향 중 "반환 누락"은 TTL 폴백으로 회수되어 무해하고 "오반환(ABA 미차단)"은 신규 슬롯을 파괴하므로, **마진은 안전한 쪽(skip)으로 기울인다.** 대가: 승급 후 `skewMargin` 이내에 예약을 끝낸 극소수의 슬롯이 조기 반환 대신 TTL 회수됨 — 처리량 손실만 있고 불변식은 안 깨진다.
   - `occurredAt`(ISO-8601 문자열)의 epoch ms 변환은 Java에서 하고 Lua에는 숫자로 넘긴다.
3. 반환: `ZREM queue:active:set:{scope} {uid}` + `DEL queue:active:{scope}:{uid}` + `DEL queue:active:first:{scope}:{uid}`(§4.6). (없는 원소 ZREM/DEL은 no-op → 중복 반환 안전, INV-4)
4. 메트릭 `xrail.queue.releases.total{scope,reason="reserved"}` 증가(Java, Lua 반환값이 released=1일 때만).

컨슈머는 `e.scheduleId()`가 null이면 schedule scope 반환을 생략한다(이벤트 스키마상 nullable — `TrainEventProducer.java:39`).

**멱등/dedup — P4 명시적 예외:** P4 규칙은 "컨슈머는 `eventId` 기준 멱등"을 요구하나, `releaseSlot`은 연산 자체가 멱등(ZREM/DEL 반복 무해)이고 ABA 가드가 순서 안전까지 겸하므로 **별도 eventId 처리셋을 두지 않는다.** 이는 P4의 의도(중복 소비 안전)를 다른 수단으로 충족하는 명시적 예외이며, 이 문단이 그 기록이다.

**DLT:** P4에 따라 `reservation.created.DLT` 연결(`DeadLetterPublishingRecoverer` + `FixedBackOff`). 반환은 멱등이라 재시도 위험 낮지만 규칙 준수.

> **왜 이벤트인가(동기 REST 아님):** train-service가 queue-service Redis(DB2)를 직접 만지면 P1(database-per-service)·P2 위반이고, 예약 응답 경로에 동기 호출을 끼우면 queue-service 장애가 예약 실패로 전파된다. 이벤트(비동기·느슨한 결합, 이미 Outbox로 트랜잭셔널)가 정답. 대가는 반환 지연 ~1–2s(Outbox 1s 폴링 + Kafka) — 자리 회수엔 무해(표 5.8).

### 4.3 TTL = no-show 폴백 (정렬 유지)

- `active-ttl-seconds`(600)를 **줄이지 않는다.** 이유: 토큰 HMAC exp와 동일 값이라(표 5.4), 슬롯 TTL만 줄이면 "슬롯은 반환됐는데 토큰은 살아있어 DB 접근 가능" → INV-2 붕괴.
- TTL은 "승급됐지만 끝내 예약 안 한 no-show"를 회수하는 폴백으로만 남긴다.
- **no-show가 처리량을 갉아먹는 문제**(표 5.1)는 TTL 단축이 아니라 §5.1-상세의 완화책(overcommit / 좌석조회 활동성 기반 조기 회수는 phase-2)으로 다룬다.
- TTL 폴백이 실제로 작동하려면 **재발급에 의한 무한 연장이 막혀 있어야 한다** — §4.6.

### 4.4 원자적 슬롯 확보 — 두 진입 경로 정합(race fix)

`enter()` fast-path(`admit`)와 스케줄러(`promoteTopN`)가 **둘 다 active 수를 늘린다.** 현재 fast-path의 `getActiveCount < maxActive` 체크는 check-then-act라 비원자적(표 5.6, 기존 잠복 버그). A 도입 후 두 경로가 공통으로 **"슬롯 1개 원자적 확보"** 헬퍼를 쓰도록 한다:
- 즉시입장: Lua로 `만료분 ZREMRANGEBYSCORE 0 now 정리 → if active < max then ZADD active; return OK else return FULL`. §4.1 승급 Lua처럼 **만료 원소를 먼저 정리하고 세야 한다** — 생략하면 만료된 stale 원소가 카운트에 남아 자리가 있는데도 FULL로 오판한다.
- 승급: §4.1 Lua가 배치 단위로 동일 원자성 보장.
- **재발급 경로(§4.6, 버킷 보유자)는 이 용량 체크를 타지 않는다.** 기존 점유자의 ZADD는 score 갱신일 뿐 카운트가 늘지 않는데, active=max 상태에서 용량 체크를 거치면 정당한 재발급이 FULL로 오거부된다. 재발급은 "체크 없이 score·버킷 TTL 리셋"만 수행.

### 4.5 좌석 조회 used 체크 — INV-2 완성 (train-service 변경 1건)

`QueueTokenInterceptor.preHandle`에서 현재 좌석 조회는 HMAC 검증만 하고 used 체크 앞에서 통과한다(`QueueTokenInterceptor.java:74`). 이대로면 `reservation.created`로 슬롯을 반환한 뒤에도, 그 사용자는 **소비된 토큰으로 좌석 조회를 HMAC 만료(최대 600s)까지 계속** 할 수 있다. 반환된 자리에 다음 대기자가 승급되므로 보호 구역 실질 동시 접근자가 `maxActive`를 넘는다(표 5.14).

**수정:** used 체크(`queue:token:used:{hmacPart}` 존재 확인)를 좌석 조회 경로에도 적용한다. 순서: HMAC 검증 → used 체크(POST·GET 공통, used면 401) → GET이면 통과(소비 없음) / POST면 `queueTokenUsedKey` attribute 세팅 후 진행. `afterCompletion`의 소비 로직은 그대로.

- **의미 변화:** "예약을 성공한 사용자는 그 순간부터 좌석 조회도 차단"된다. 이미 예약을 마쳐 보호 구역을 떠난 사용자이므로 UX 손실이 아니라 설계 의도다. 재조회가 필요하면 재진입(대기열)하면 된다.
- **배포 독립성:** 이 변경은 큐-토큰 소비 이후의 GET만 추가로 차단하므로 하위호환이고, queue-service의 A 배포와 **순서 무관하게 독립 배포 가능**하다(Q5의 검증 로직 동조는 불변 — HMAC/exp 계산은 안 바뀐다).

### 4.6 재발급 세션 절대 상한 — 무한 슬롯 연장 차단

`enter()`는 버킷 존재 시 토큰을 재발급한다(`QueueService.java:52`, 2026-06-11 used-토큰 부활 버그의 fix). 문제는 `admit()` 재호출이 **버킷 TTL(600s)과 active-set score를 매번 리셋**한다는 것: 클라이언트가 예약 없이 주기적으로 `enter()`만 호출하면 슬롯을 무기한 보유한다. rate 방식에선 무해했지만 A에서는 no-show 회수의 유일한 수단(TTL 폴백)이 무력화되어 INV-3이 깨진다(표 5.15).

단순 수정이 불가능한 **구조적 충돌**이 있다:
- "재발급 시 TTL 연장 금지"만 하면 → 새 토큰은 `issuedAt+600s`까지 유효(`QueueTokenInterceptor.java:119`)한데 슬롯은 먼저 죽음 → 빈 자리가 리필된 뒤에도 옛 토큰이 살아있음 → INV-2 붕괴.
- "옛 issuedAt으로 재발급"하면 → HMAC이 결정적이라 소비된 토큰이 그대로 부활 → 2026-06-11 버그 재발.

**phase-1 해법 — 세션 절대 상한(cap):**
- **새 세션 admit**(버킷 부재 상태에서의 슬롯 점유 — 즉시입장·승급 모두) 시 `queue:active:first:{scope}:{userId}` = 최초 issuedAt 저장 (TTL = `session-cap + active-ttl + 60s` 여유, ERD.md §5.3에 키 추가). **반드시 무조건 SET(덮어쓰기)로 쓴다 — SETNX 금지.** 직전 세션이 TTL 만료나 leave로 끝나면 first-키가 잔존할 수 있는데(TTL이 세션보다 길다), SETNX면 새 세션이 시작하자마자 옛 firstIssuedAt 기준으로 cap에 걸린다. 재발급 경로의 admit에서는 first-키를 건드리지 않는다(갱신하면 cap이 무의미).
- `leave()`도 버킷·zset과 함께 first-키를 DEL한다(재진입 시 새 세션 보장).
- `enter()` 재발급 시: `now - firstIssuedAt ≤ session-cap-seconds`(기본 600)이면 기존대로 재발급(admit, TTL 리셋 — INV-2 유지 위해 토큰 exp와 슬롯 TTL이 함께 연장되는 것은 의도). first-키가 없으면(비정상 잔존 상태) 새 세션으로 간주해 first-키를 SET하고 재발급한다.
- **cap 초과면 재발급 거부: 연장 없이 기존 버킷 값(토큰)을 그대로 반환한다.** 슬롯·버킷·기존 토큰은 마지막 재발급 시점 + 600s에 함께 만료(INV-2 유지). 슬롯을 즉시 삭제하지 않는 이유: 삭제하면 마지막 토큰이 잔여 시간 동안 살아있는 채 자리가 리필되어 INV-2가 깨진다.
  - ⚠ 이 "기존 토큰 그대로 반환"은 2026-06-11 버그(소비된 토큰 반환)와 같은 패턴이지만 무해한 이유: 반환되는 토큰이 소비된(used) 토큰인 경우는 "예약 성공 직후 ~ `reservation.created` 도착 전"의 1–2s 창뿐이고, 그 사용자는 이미 예약을 마쳐 보호 구역을 떠났다. 이벤트가 도착하면 버킷·first-키가 DEL되어 다음 `enter()`는 새 세션으로 시작한다. cap 초과 상태의 no-show가 받는 토큰은 미소비라 문제없다.
- 효과: 한 세션의 총 슬롯 점유는 **최대 ≈ `session-cap + active-ttl`(기본 20분)로 유계**. 정상 사용자(예약 완료)는 `reservation.created`로 훨씬 일찍 반환되고, first-키도 그때 함께 DEL(§4.2)되어 재진입 시 새 세션으로 시작한다.

**phase-2 대안(D6):** 토큰 페이로드에 명시적 exp 추가(`userId:scope:issuedAt:exp`, 토큰=`hmac.issuedAt.exp`) — 재발급 시 issuedAt은 새로(used 충돌 회피), exp는 고정(연장 원천 차단). 근본적이지만 Q5 동조(common-lib + train-service + queue-service 동시 변경)와 롤아웃 버전 결합이 생기므로 phase-1 범위 밖.

---

## 5. 연쇄 반응 · 결함 분석 (필수 검토 항목)

> "A로 바꿀 때 다른 지점에서 새로 터지는 문제"를 전부 나열한다. 각 항목: 무엇이/영향/완화. 본문 참조는 "표 5.x"(아래 행) 또는 "§5.x-상세"(하단 상세 절)로 표기한다.

| # | 연쇄 지점 | 무엇이 터지나 | 심각도 | 완화 |
|---|-----------|--------------|:---:|------|
| 5.1 | **no-show가 처리량을 직접 감소** | 자리 기반이라, 승급자가 예약 없이 방치하면 그 슬롯이 TTL(600s)까지 안 빠져 대기열이 사실상 정지. rate 방식엔 없던 신규 문제 | ★★★ | §5.1-상세 |
| 5.2 | **대기시간 추정 붕괴** | `estimateWait=ceil(rank/100)*3`(`QueueController.java:133`, `QueueScheduler.java:110`)은 "100/3s 고정 처리량" 가정. A에선 실제 처리량=반환율이라 추정이 크게 빗나감("3분"→실제 30분) | ★★ | §5.2-상세 |
| 5.3 | **기아/프리징 + 재시도 폭주** | 자리가 다 차고 아무도 반환 안 하면 rank가 얼어붙음 → 사용자가 새로고침/이탈·재진입 반복 → leave/enter 폭주 | ★★ | §5.3-상세 |
| 5.4 | **TTL/토큰 exp 결합** | 슬롯 TTL을 토큰 exp보다 줄이면 슬롯 반환 후에도 토큰이 살아 DB 접근 가능 → INV-2 붕괴. 두 값은 train-service와 반드시 동일(Q5) | ★★★ | §4.3, 줄이지 않음 |
| 5.5 | **ABA(지연 반환이 신규 슬롯 삭제) + 클록 skew** | 예약→즉시 재진입→재승급 후, 지연된 옛 `reservation.created` 반환이 새 슬롯을 ZREM. 가드의 비교 대상이 서로 다른 서비스 클록(issuedAt=queue, occurredAt=train)이라 skew가 오판 유발 | ★★ | §4.2 ABA 가드 + skew 마진(오판을 안전측=skip으로) |
| 5.6 | **check-then-act 경쟁(기존 잠복)** | active=99에서 동시 요청 2개가 둘 다 `99<100` 통과→101. 현재도 존재하나 A에서 상한이 의미를 가지며 표면화 | ★★ | §4.4 원자적 슬롯 확보(Lua) |
| 5.7 | **scope 매핑 + 용량 합산 + 임의 scope 생성** | ① 반환 컨슈머가 scope를 알아야 ZREM 대상 키 결정 — `enter()`가 임의 scope를 받으므로 "global 고정" 가정은 강제되지 않음. ② scope가 N개 활성이면 실효 동시성 = N×maxActive인데 보호 자원(DB 풀)은 전역. ③ `QueueEnterRequest`는 `@NotBlank`만 검증 — 클라이언트가 scope 문자열을 마음대로 만들면 상한이 사실상 무한(상한 우회 벡터) | ★★ | ① §4.2 global+schedule 이중 반환(멱등이라 무해) ② D1에서 scope별 상한 합계 ≤ 풀 크기로 결정 ③ **phase-1: `enter()`에 scope 허용목록 검증 추가(`global`만 허용, 위반 시 400)** — scope 확장 시 D1과 함께 해제 |
| 5.8 | **반환 지연(~1–2s)** | Outbox 1s 폴링+Kafka로 자리 회수가 즉각적이지 않음 | ★ | 자리 회수엔 무해. 즉시성 필요 시 표 5.10 |
| 5.9 | **FORCE_OFF와 INV-1 충돌** | `FORCE_OFF`는 무조건 즉시입장(상한 무시)이라 동시 인원이 maxActive를 넘음 | ★ | 운영자 명시적 override로 문서화(경보만) |
| 5.10 | **다중 인스턴스(향후)** | 승급 Lua는 인스턴스 안에서 원자적이지만, 반환 컨슈머 groupId 공유 시 파티셔닝 필요. Q3 단일 인스턴스 가정 유지 | ★ | phase-1 단일 인스턴스, 확장점 명시 |
| 5.11 | **train→queue 이벤트 결합 신설** | queue-service가 train 이벤트를 처음 소비 → 방향성 결합 발생. 단 P1 choreography에서 이벤트 소비는 허용된 결합 | ★ | 허용됨. 직접 REST/DB 접근이 아니라 이벤트라 OK |
| 5.12 | **enter() 재발급 경로와 반환의 상호작용** | 반환으로 버킷을 DEL하면, 예약 완료자가 재진입 시 "신규"로 취급 → 재큐잉(정상). 단 반환 이벤트 도착 *전* 재진입은 재발급 경로를 타므로 표 5.15와 함께 검토 | ★ | 의도된 동작. §4.6 cap이 남용 차단 |
| 5.13 | **좌석조회 반복 = 슬롯 정당 보유** | 좌석만 계속 조회(토큰 미소비)하는 사용자는 `reservation.created`를 안 냄 → 슬롯 TTL까지 보유. 이는 결함이 아니라 "보호 구역에 실제로 머무는 중"이라 정상 | ✓ | 정상. no-show(5.1)와 구분 |
| 5.14 | **소비된 토큰의 좌석 조회 생존 (INV-2 구멍)** | used 체크가 POST 전용이라(`QueueTokenInterceptor.java:74`), 슬롯 반환 후에도 소비된 토큰으로 GET .../seats가 HMAC 만료까지 통과 → 실질 동시 접근 > maxActive | ★★★ | §4.5 좌석 조회 used 체크(train-service 1건) |
| 5.15 | **enter() 재발급 = 무한 슬롯 연장** | 버킷 존재 시 admit() 재호출이 TTL·score를 매번 리셋 → enter() 폴링만으로 슬롯 영구 보유 → TTL 폴백(유일한 no-show 회수) 무력화, INV-3 붕괴. 단순 수정은 INV-2 또는 used-토큰 부활과 충돌하는 구조적 문제 | ★★★ | §4.6 세션 절대 상한(phase-1) / 토큰 exp 필드(D6, phase-2) |
| 5.16 | **leave 후 토큰 생존 (상한 우회)** | leave는 자리만 반환, stateless 토큰은 못 죽임 → enter→leave→미사용 토큰으로 예약 반복 시 INV-1 우회 가능. used-키는 train Redis(DB1)라 queue가 못 지움(P5) | ★★ | 정직한 클라이언트 가정 하 수용(phase-1). `releases.total{reason="left"}` 급증 경보로 감시. 근본 해법은 D6(토큰 exp)로도 부족 — stateful 검증 필요라 범위 밖 |
| 5.17 | **멱등 재시도 POST가 이벤트 없이 토큰 소비** | `create()`의 idempotency 히트(기존 예약 반환)는 2xx라 `afterCompletion`이 새 토큰을 used 처리하지만 `doCreate`를 안 타 `reservation.created` 미발행 → 그 슬롯은 조기 반환 없이 TTL까지 잔존 | ★ | 수용. TTL 폴백으로 회수되고 빈도 낮음(재진입 후 같은 idem-key 재사용 케이스). 불변식 영향 없음 |

### 5.1-상세 — no-show 완화

가장 중요한 신규 트레이드오프. 선택지:

- **(권장, phase-1) Overcommit 계수:** `effectiveMax = maxActive × (1 + noShowRate)`. 관측된 반환율/no-show율로 조정. 커넥션 풀 대비 약간의 여유(예: prod 풀 100 → maxActive 100, 하지만 실제 동시 접근자는 no-show만큼 항상 하회)를 활용. **가장 단순하고 INV-2를 안 깬다.**
- **(전제) §4.6 세션 상한:** no-show가 enter() 폴링으로 TTL을 무한 리셋하면 어떤 완화책도 소용없다 — cap이 먼저다.
- **(phase-2) 활동성 기반 조기 회수:** `GET .../seats` 접근 시 슬롯 TTL을 슬라이딩 갱신(lease renewal)하고, 미갱신 슬롯을 짧게 회수. 단 이건 **train→queue 활동성 신호가 필요**하고 INV-2를 지키려면 "토큰 무효화"까지 얽혀 커짐 → phase-1 범위 밖으로 명시.
- **하지 말 것:** 슬롯 TTL만 단독 단축(표 5.4로 INV-2 붕괴).

### 5.2-상세 — 대기시간 추정 수정

`estimateWait`를 실제 드레인율 기반으로 교체:
- `xrail.queue.releases.total`의 최근 이동평균(초당 반환 수) `r`을 산출 → `expectedWaitSeconds ≈ rank / max(r, ε)`.
- **콜드스타트 폴백:** 배포 직후·한산 구간처럼 반환 표본이 없으면(r≈0) 위 식이 발산한다. 표본이 임계치(예: 최근 60s 내 반환 ≥ 5건) 미만이면 기존 공식 `ceil(rank/batchSize)*3`으로 폴백한다.
- 최소한 두 곳(`QueueController.java:133`, `QueueScheduler.java:110`)의 하드코딩 중복을 `QueueService` 단일 메서드로 통합(현재 로직 중복도 정리).

### 5.3-상세 — 기아/프리징 완화

- §5.1-상세 overcommit으로 자리가 완전히 얼어붙는 상황 자체를 줄임.
- rank가 안 움직여도 SSE는 살아있게(heartbeat 유지) 하고, 프론트가 rank 정체를 "정상 대기"로 표시(재시도 유발 금지). 재진입해도 Q6 idempotency로 순번은 유지되므로 폭주 영향은 제한적.

---

## 6. 변경 파일 체크리스트

| 파일 | 변경 |
|------|------|
| `queue-service/.../service/QueueService.java` | `promoteTopN`을 capacity-aware+Lua로; `admit` fast-path 원자화; 재발급 세션 상한 + first-키 덮어쓰기 SET(§4.6); `leave()`에 first-키 DEL 추가; `enter()` scope 허용목록 검증(표 5.7 ③); `getActiveCount` 재사용; `active.size` 게이지 등록; wait 추정 단일화(+콜드스타트 폴백) |
| `queue-service/src/main/resources/lua/promote.lua` (신규) | §4.1 승급 원자 스크립트(토큰 issuedAt=ARGV now 정렬 포함) |
| `queue-service/src/main/resources/lua/release.lua` (신규) | §4.2 반환 원자 스크립트(버킷 부재 skip + ABA 가드 + ZREM/DEL 원자 실행) |
| `queue-service/.../kafka/ReservationCreatedConsumer.java` (신규) | §4.2 슬롯 반환 컨슈머(global+schedule 이중 반환, scheduleId null 가드, skew 마진) + DLT |
| `queue-service/.../config/KafkaConsumerConfig.java` (신규/확인) | 컨슈머 팩토리·`ErrorHandler`·DLT recoverer (train-service 패턴 참조) |
| `queue-service/.../scheduler/QueueScheduler.java` | wait 추정 로직 이관, 승급 반환값 처리 유지 |
| `queue-service/.../controller/QueueController.java` | `estimateWait` 중복 제거(서비스 위임) |
| `queue-service/src/main/resources/application.yml` | `admission.overcommit`, `admission.session-cap-seconds`, `admission.release-skew-margin-ms`(신규 knob); TTL은 유지 |
| `train-service/.../interceptor/QueueTokenInterceptor.java` | §4.5 좌석 조회에도 used 체크 적용(HMAC/exp 계산 불변 — Q5 동조 유지) |
| `docs/ERD.md` §5.3 | `queue:active:*` 반환 트리거·TTL 의미 갱신, `queue:active:first:{scope}:{userId}` 키 추가(§4.6, TTL 명시) |
| `docs/ARCHITECTURE.md` §5 | 승급 시퀀스 + 반환 이벤트 흐름 반영, `xrail.queue.active.size`/`releases.total` 메트릭 확정 |

> **common-lib 변경 없음. train-service는 §4.5 한 건만 변경**(하위호환·독립 배포 가능 — §8). 반환 신호로 기존 `reservation.created`를 재사용하므로 새 이벤트/토픽/필드 불필요(CLAUDE.md §2 단순성). "보호 구역 이탈"을 예약 성공과 분리하고 싶어지면 그때 전용 이벤트 도입을 재검토(D3).

---

## 7. 관측성 · 검증

**메트릭(추가/확정):**
- `xrail.queue.active.size{scope}` Gauge (문서엔 있으나 코드 미구현 — 이번에 실제 등록)
- `xrail.queue.releases.total{scope,reason}` Counter (reserved/left/expired) — `reason="left"` 급증은 표 5.16 남용 신호로 경보
- `xrail.queue.promote.skipped{scope}` Counter (자리 없어 승급 0인 tick)
- `xrail.queue.reissue.capped.total{scope}` Counter (§4.6 상한 도달로 재발급 거부)

**단위/통합 테스트(P8):**
- 승급 Lua: active가 maxActive면 승급 0 / 여유 k면 min(k,batch)만 / waiting 부족 시 있는 만큼.
- 즉시입장 Lua: 만료된 stale 원소만 가득한 active-set → 정리 후 OK(FULL 오판 없음). active=max에서 기존 점유자의 재발급 → 거부되지 않음(§4.4).
- 반환 멱등: 동일 `reservation.created` 2회 → 슬롯 1회만 감소.
- 반환 원자성: 반환과 동시 `enter()` 재발급 경쟁 → 새 세션(갱신된 issuedAt) 보존(release.lua 단일 실행 검증).
- 버킷 부재 반환: 버킷 없는 uid의 반환 이벤트 → zset 원소 보존(skip) 확인.
- ABA: 예약→재진입→재승급 후 지연 반환 도착 → 신규 슬롯 보존. **skew 마진: `issuedAt ∈ [occurredAt - margin, occurredAt]`이면 skip(반환 안 함)** 확인.
- scope 이중 반환: `schedule:{id}` scope로 승급된 슬롯이 반환되는지.
- §4.5: 예약 성공(토큰 소비) 후 동일 토큰으로 `GET .../seats` → 401.
- §4.6: cap 이내 재발급은 연장, cap 초과 재발급은 기존 토큰 반환+TTL 연장 없음, 총 세션 ≤ cap+ttl. 예약 완료(반환) 후 재진입은 first-키가 지워져 새 세션으로 시작. **TTL 만료/leave 후 재진입(잔존 first-키) → 덮어쓰기 SET으로 새 세션 시작(즉시 cap 안 걸림)** 확인.
- scope 검증: `global` 외 scope로 `enter()` → 400.
- 원자성: `@EmbeddedKafka` + 동시 enter 다발 → active ≤ maxActive (INV-1).
- E2E 시나리오: maxActive=5, waiting=20, 승급자 중 3명 예약/2명 no-show → 다음 tick 3자리만 채워지는지(=이전 성공만큼만 리필) 확인.

---

## 8. 롤아웃 순서

1. common-lib 변경 없음 → 재배포 불필요.
2. **train-service(§4.5)와 queue-service(A)는 순서 무관 독립 배포.** §4.5는 소비된 토큰의 GET만 추가 차단하는 하위호환 변경이고, HMAC/exp 계산이 안 바뀌므로 Q5 위반 없음. 단 **INV-2가 완전해지는 것은 둘 다 배포된 시점**이므로, 같은 릴리스 윈도우에 배포한다.
3. Redis 상태는 ephemeral(Q1) — 기존 active 항목은 TTL로 자연 소멸, 마이그레이션 불필요. 신규 first-키(§4.6)는 배포 후 자연 생성.
   - **최초 배포 시 이벤트 히스토리 재생:** 컨슈머 yml이 `auto-offset-reset: earliest`고 `queue-service` groupId의 커밋 오프셋이 없으므로, 첫 기동 시 `reservation.created` 전체 히스토리를 재생한다. 가드(버킷 부재 skip + ABA skip)로 전부 no-op이라 무해하지만, 재생량이 크면 기동 직후 컨슈머 lag 경보가 잠깐 뜰 수 있다 — 원치 않으면 배포 전 해당 groupId 오프셋을 latest로 세팅해 두거나 이 리스너만 `auto-offset-reset=latest`로 오버라이드.
4. 배포 후 `active.size`가 maxActive를 상회하지 않는지, `promote.skipped`/`releases.total` 균형, `reissue.capped`·`releases{reason="left"}` 추이를 대시보드로 확인.

---

## 9. 미결 결정사항 (구현 착수 전 확정 필요)

- **D1. maxActive 실제 값:** DB 커넥션 풀(prod 100)과 정렬. maxActive=풀크기? 여유 마진? (§5.1-상세 overcommit과 함께 결정) **scope가 2개 이상 활성화될 수 있다면 "scope별 상한의 합 ≤ 풀 크기"가 되도록 배분 규칙까지 포함해 결정할 것(표 5.7 ②).**
- **D2. overcommit 도입 여부(phase-1):** no-show 완화를 phase-1에 넣을지, 순수 자리기반으로 먼저 내보내고 관측 후 튜닝할지.
- **D3. 반환 신호원:** `reservation.created` 재사용(권장) vs 전용 `queue.exited` 이벤트 신설.
- **D4. no-show TTL 값:** 600s 유지 vs "예약 세션 현실값"으로 조정(단, train-service 토큰 exp 동조 필수 — 표 5.4).
- **D5. 좌석조회 활동성 기반 조기 회수(§5.1-상세 phase-2)를 로드맵에 넣을지.**
- **D6. 토큰 exp 필드 도입(phase-2):** §4.6의 세션 상한은 우회 불가한 유계를 주지만 "cap 이내 연장 허용"이라는 타협이다. 토큰 페이로드에 exp를 넣으면(`hmac.issuedAt.exp`) 연장을 원천 차단하고 표 5.16의 부분 완화도 가능하나, common-lib·train-service·queue-service 동시 변경(Q5 동조)과 롤아웃 결합이 생긴다. phase-1 관측(`reissue.capped` 추이) 후 결정.
- **D7. `session-cap-seconds`·`release-skew-margin-ms` 기본값:** cap 기본 600s(총 점유 상한 ≈20분), 마진 기본 2s 제안 — 부하테스트에서 NTP skew 실측 후 확정.
