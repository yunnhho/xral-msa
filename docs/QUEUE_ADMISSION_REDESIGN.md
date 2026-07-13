# 대기열 진입 제어 재설계 — Rate 방식 → Concurrency(자리 기반) 방식(A)

> 상태: 설계(구현 전) · 대상 서비스: `queue-service`(주), `train-service`(부), `common-lib`
> 관련 문서: [ARCHITECTURE.md](./ARCHITECTURE.md) §5 · [ERD.md](./ERD.md) §5.3 · [problem.md](./problem.md) 성과3 · queue-service/CLAUDE.md Q2
> 목적: 이 문서는 "무엇을 왜 바꾸는지 + 바꿀 때 다른 지점에서 터지는 연쇄 결함"을 미리 확정해 매끄러운 구현을 돕는다.

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

- **INV-1 (동시성 상한):** 임의 시점에 `queue:active:set:{scope}`의 유효 원소 수 ≤ `maxActive`. (운영자 `FORCE_OFF` 예외 — §5.12)
- **INV-2 (슬롯 = DB 접근권):** active 슬롯을 가진 사용자만 보호 구역(train-service 예약 생성·좌석 조회)에 접근할 수 있고, 슬롯이 없으면 접근할 수 없어야 한다. 즉 **"슬롯 반환 ⇒ 토큰 사용 불가"가 성립**해야 한다.
- **INV-3 (진행 보장):** 승급 경로가 살아있는 한, 슬롯이 비면 반드시 다음 대기자가 채워진다(기아 없음).
- **INV-4 (멱등/순서 안전):** 슬롯 반환 신호는 at-least-once·순서 뒤섞임에도 안전(중복 반환 no-op, 지연 반환이 신규 세션을 침범하지 않음).

> **INV-2가 이 설계의 핵심 난제**다. 토큰은 stateless HMAC이라 train-service가 서명+발급시각(age)만으로 검증한다(`QueueTokenInterceptor.java:106`). queue-service가 슬롯을 지워도 토큰 자체는 죽지 않는다. 따라서 **"슬롯을 반환하는 순간 = 토큰이 더 이상 통하지 않는 순간"인 트리거만 골라야 한다.** (§4.2 참조)

---

## 3. 핵심 통찰 — 보호 구역의 경계와 슬롯 생명주기

"보호 구역"을 정확히 그으면 나머지 설계가 자동으로 따라온다.

- 큐 토큰이 실제로 게이팅하는 것은 **`POST /api/reservations`(1회 소비) + `GET .../seats`(검증만)** 두 엔드포인트뿐이다(`QueueTokenInterceptor.java:44-46`).
- **결제는 게이팅되지 않는다.** 예약 PENDING 후 20분 결제 창(`EXPIRES_MINUTES=20`)은 보호 구역 밖이다.
- 따라서 사용자가 보호 구역을 "떠나는" 시점은 곧 **예약 생성이 성공한 시점**이다. 그 뒤 사용자는 큐-게이팅 엔드포인트를 다시 건드리지 않는다.

**결정적 사실:** `reservation.created` 이벤트는 **예약 성공 시에만** 발행된다(`ReservationService.doCreate` → `publishReservationCreated`, `ReservationService.java:153`). 좌석 충돌(409)로 실패한 재시도에서는 발행되지 않고, 이때 토큰은 `used` 처리되지 않아(`afterCompletion`은 2xx에서만 `used` 세팅, `QueueTokenInterceptor.java:100`) 사용자는 슬롯을 정당하게 계속 보유한다.

→ **`reservation.created` = "토큰 소비 완료 = 보호 구역 이탈"을 정확히 만족하는 유일한 조기 반환 신호.** INV-2를 자연히 충족한다(이 시점 이후 토큰은 `used`라 재사용 불가).

### 슬롯 생명주기(to-be)

```
승급(promote) ──▶ ACTIVE ──┬── reservation.created 수신  → 슬롯 반환(정상, 조기)
                           ├── POST /leave (자발 이탈)   → 슬롯 반환
                           └── TTL 만료 (no-show/미완료)  → 슬롯 반환(폴백)
```

세 경로 모두 "반환 = 토큰이 이미 못 쓰이거나(used/만료) 사용자가 스스로 포기"라서 INV-2를 깨지 않는다. (단축 TTL·하트비트 기반 반환은 INV-2를 깨므로 채택하지 않는다 — §5.4 근거)

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
- **원자성:** check(getActiveCount) → act(promote)가 분리되면 경쟁이 생긴다(§5.9). queue-service/CLAUDE.md **Q2가 이미 "승급은 Lua atomic 권장"**이라 명시. 승급을 단일 Lua 스크립트로 구현한다:
  - KEYS: `queue:waiting:{scope}`, `queue:active:set:{scope}`
  - ARGV: `maxActive`, `batchSize`, `now`, `expiresAt`(=now+ttl)
  - 로직: `ZCARD active - (만료분 ZREMRANGEBYSCORE 0 now)` 로 현재 active 계산 → `n=min(batch, max-active)` → `ZRANGE waiting 0 n-1` → 각 uid를 active에 ZADD(score=expiresAt) + waiting에서 ZREM → 승급된 uid 목록 반환.
  - 토큰 발급(HMAC)·`queue:active:{scope}:{uid}` 버킷 SET은 Lua 밖(Java)에서 반환된 uid로 수행하거나, 토큰을 ARGV로 미리 만들어 넘긴다. **버킷 SET과 active-set ZADD의 순서/정합**은 §5.9 참조.

### 4.2 슬롯 반환 — `reservation.created` 소비

queue-service에 **첫 Kafka 컨슈머**를 추가(현재 컨슈머 없음, spring-kafka 의존성은 존재).

```
@KafkaListener(topics = Topics.RESERVATION_CREATED, groupId = "queue-service")
onReservationCreated(ReservationCreatedEvent e):
    scope = deriveScope(e)          // phase-1: "global" 고정 (§5.7)
    releaseSlot(scope, e.userId(), e.occurredAt())
```

`releaseSlot`:
1. **ABA 가드(INV-4):** `queue:active:{scope}:{uid}` 버킷 값(토큰) 끝의 `issuedAt`을 파싱. `issuedAt > occurredAt`(=이 이벤트가 소비한 토큰보다 나중에 발급된 토큰)이면 **사용자가 이미 재진입해 새 슬롯을 받은 것** → **반환하지 않고 return**. (지연 도착한 옛 반환이 신규 세션을 침범하는 것 차단)
2. 아니면 `ZREM queue:active:set:{scope} {uid}` + `DEL queue:active:{scope}:{uid}`. (없는 원소 ZREM은 no-op → 중복 반환 안전, INV-4)
3. 메트릭 `xrail.queue.releases.total{scope,reason="reserved"}` 증가.

**멱등/dedup:** 컨슈머는 P4 규칙상 `eventId` 기준 멱등이어야 하나, `releaseSlot`이 본질적으로 멱등(ZREM/DEL 반복 무해)이라 별도 처리셋 없이도 안전. 단 ABA 가드가 그 역할을 겸한다.

**DLT:** P4에 따라 `reservation.created.DLT` 연결(`DeadLetterPublishingRecoverer` + `FixedBackOff`). 반환은 멱등이라 재시도 위험 낮지만 규칙 준수.

> **왜 이벤트인가(동기 REST 아님):** train-service가 queue-service Redis(DB2)를 직접 만지면 P1(database-per-service)·P2 위반이고, 예약 응답 경로에 동기 호출을 끼우면 queue-service 장애가 예약 실패로 전파된다. 이벤트(비동기·느슨한 결합, 이미 Outbox로 트랜잭셔널)가 정답. 대가는 반환 지연 ~1–2s(Outbox 1s 폴링 + Kafka) — 자리 회수엔 무해(§5.8).

### 4.3 TTL = no-show 폴백 (정렬 유지)

- `active-ttl-seconds`(600)를 **줄이지 않는다.** 이유: 토큰 HMAC exp와 동일 값이라(§5.4), 슬롯 TTL만 줄이면 "슬롯은 반환됐는데 토큰은 살아있어 DB 접근 가능" → INV-2 붕괴.
- TTL은 "승급됐지만 끝내 예약 안 한 no-show"를 회수하는 폴백으로만 남긴다.
- **no-show가 처리량을 갉아먹는 문제**(§5.3)는 TTL 단축이 아니라 §5.3의 완화책(overcommit / 좌석조회 활동성 기반 조기 회수는 phase-2)으로 다룬다.

### 4.4 원자적 슬롯 확보 — 두 진입 경로 정합(race fix)

`enter()` fast-path(`admit`)와 스케줄러(`promoteTopN`)가 **둘 다 active 수를 늘린다.** 현재 fast-path의 `getActiveCount < maxActive` 체크는 check-then-act라 비원자적(§5.9, 기존 잠복 버그). A 도입 후 두 경로가 공통으로 **"슬롯 1개 원자적 확보"** 헬퍼를 쓰도록 한다:
- 즉시입장: Lua로 `if active < max then ZADD active; return OK else return FULL`.
- 승급: §4.1 Lua가 배치 단위로 동일 원자성 보장.

---

## 5. 연쇄 반응 · 결함 분석 (필수 검토 항목)

> "A로 바꿀 때 다른 지점에서 새로 터지는 문제"를 전부 나열한다. 각 항목: 무엇이/영향/완화.

| # | 연쇄 지점 | 무엇이 터지나 | 심각도 | 완화 |
|---|-----------|--------------|:---:|------|
| 5.1 | **no-show가 처리량을 직접 감소** | 자리 기반이라, 승급자가 예약 없이 방치하면 그 슬롯이 TTL(600s)까지 안 빠져 대기열이 사실상 정지. rate 방식엔 없던 신규 문제 | ★★★ | §5.3 |
| 5.2 | **대기시간 추정 붕괴** | `estimateWait=ceil(rank/100)*3`(`QueueController.java:133`, `QueueScheduler.java:110`)은 "100/3s 고정 처리량" 가정. A에선 실제 처리량=반환율이라 추정이 크게 빗나감(“3분”→실제 30분) | ★★ | §5.5 |
| 5.3 | **기아/프리징 + 재시도 폭주** | 자리가 다 차고 아무도 반환 안 하면 rank가 얼어붙음 → 사용자가 새로고침/이탈·재진입 반복 → leave/enter 폭주 | ★★ | §5.6 |
| 5.4 | **TTL/토큰 exp 결합** | 슬롯 TTL을 토큰 exp보다 줄이면 슬롯 반환 후에도 토큰이 살아 DB 접근 가능 → INV-2 붕괴. 두 값은 train-service와 반드시 동일(Q5) | ★★★ | §4.3, 줄이지 않음 |
| 5.5 | **ABA(지연 반환이 신규 슬롯 삭제)** | 사용자가 예약→즉시 재진입→재승급(새 슬롯) 후, 지연된 옛 `reservation.created` 반환이 새 슬롯을 ZREM | ★★ | §4.2 ABA 가드(issuedAt 비교) |
| 5.6 | **check-then-act 경쟁(기존 잠복)** | active=99에서 동시 요청 2개가 둘 다 `99<100` 통과→101. 현재도 존재하나 A에서 상한이 의미를 가지며 표면화 | ★★ | §4.4 원자적 슬롯 확보(Lua) |
| 5.7 | **scope 매핑** | 반환 컨슈머가 `reservation.created`→scope를 알아야 ZREM 대상 키 결정. phase-1은 `global` 고정이라 자명, `schedule:{id}` 확장 시 이벤트의 `scheduleId`로 파생 필요 | ★ | phase-1 `global`, 확장점 명시 |
| 5.8 | **반환 지연(~1–2s)** | Outbox 1s 폴링+Kafka로 자리 회수가 즉각적이지 않음 | ★ | 자리 회수엔 무해. 즉시성 필요 시 §5.10 |
| 5.9 | **FORCE_OFF와 INV-1 충돌** | `FORCE_OFF`는 무조건 즉시입장(상한 무시)이라 동시 인원이 maxActive를 넘음 | ★ | 운영자 명시적 override로 문서화(경보만) |
| 5.10 | **다중 인스턴스(향후)** | 승급 Lua는 인스턴스 안에서 원자적이지만, 반환 컨슈머 groupId 공유 시 파티셔닝 필요. Q3 단일 인스턴스 가정 유지 | ★ | phase-1 단일 인스턴스, 확장점 명시 |
| 5.11 | **train→queue 이벤트 결합 신설** | queue-service가 train 이벤트를 처음 소비 → 방향성 결합 발생. 단 P1 choreography에서 이벤트 소비는 허용된 결합 | ★ | 허용됨. 직접 REST/DB 접근이 아니라 이벤트라 OK |
| 5.12 | **enter() 재발급 경로와 반환의 상호작용** | `enter()`는 active 버킷 존재 시 토큰 재발급(`QueueService.java:52`). 반환으로 버킷을 DEL하면, 예약 완료자가 재진입 시 "신규"로 취급 → 재큐잉(정상) | ★ | 의도된 동작. 단 §5.5 ABA와 함께 검토 |
| 5.13 | **좌석조회 반복 = 슬롯 정당 보유** | 좌석만 계속 조회(토큰 미소비)하는 사용자는 `reservation.created`를 안 냄 → 슬롯 TTL까지 보유. 이는 결함이 아니라 "보호 구역에 실제로 머무는 중"이라 정상 | ✓ | 정상. no-show(§5.1)와 구분 |

### 5.3 no-show 완화 (5.1의 상세)

가장 중요한 신규 트레이드오프. 선택지:

- **(권장, phase-1) Overcommit 계수:** `effectiveMax = maxActive × (1 + noShowRate)`. 관측된 반환율/no-show율로 조정. 커넥션 풀 대비 약간의 여유(예: prod 풀 100 → maxActive 100, 하지만 실제 동시 접근자는 no-show만큼 항상 하회)를 활용. **가장 단순하고 INV-2를 안 깬다.**
- **(phase-2) 활동성 기반 조기 회수:** `GET .../seats` 접근 시 슬롯 TTL을 슬라이딩 갱신(lease renewal)하고, 미갱신 슬롯을 짧게 회수. 단 이건 **train→queue 활동성 신호가 필요**하고 INV-2를 지키려면 "토큰 무효화"까지 얽혀 커짐 → phase-1 범위 밖으로 명시.
- **하지 말 것:** 슬롯 TTL만 단독 단축(§5.4로 INV-2 붕괴).

### 5.5 대기시간 추정 수정 (5.2의 상세)

`estimateWait`를 실제 드레인율 기반으로 교체:
- `xrail.queue.releases.total`의 최근 이동평균(초당 반환 수) `r`을 산출 → `expectedWaitSeconds ≈ rank / max(r, ε)`.
- 최소한 두 곳(`QueueController.java:133`, `QueueScheduler.java:110`)의 하드코딩 중복을 `QueueService` 단일 메서드로 통합(현재 로직 중복도 정리).

### 5.6 기아/프리징 완화 (5.3의 상세)

- §5.3 overcommit으로 자리가 완전히 얼어붙는 상황 자체를 줄임.
- rank가 안 움직여도 SSE는 살아있게(heartbeat 유지) 하고, 프론트가 rank 정체를 "정상 대기"로 표시(재시도 유발 금지). 재진입해도 Q6 idempotency로 순번은 유지되므로 폭주 영향은 제한적.

---

## 6. 변경 파일 체크리스트

| 파일 | 변경 |
|------|------|
| `queue-service/.../service/QueueService.java` | `promoteTopN`을 capacity-aware+Lua로; `admit` fast-path 원자화; `getActiveCount` 재사용; `active.size` 게이지 등록; wait 추정 단일화 |
| `queue-service/src/main/resources/lua/promote.lua` (신규) | §4.1 승급 원자 스크립트 |
| `queue-service/.../kafka/ReservationCreatedConsumer.java` (신규) | §4.2 슬롯 반환 컨슈머 + DLT |
| `queue-service/.../config/KafkaConsumerConfig.java` (신규/확인) | 컨슈머 팩토리·`ErrorHandler`·DLT recoverer (train-service 패턴 참조) |
| `queue-service/.../scheduler/QueueScheduler.java` | wait 추정 로직 이관, 승급 반환값 처리 유지 |
| `queue-service/.../controller/QueueController.java` | `estimateWait` 중복 제거(서비스 위임) |
| `queue-service/src/main/resources/application.yml` | `admission.overcommit`(신규 knob) 등; TTL은 유지 |
| `docs/ERD.md` §5.3 | `queue:active:*` 반환 트리거·TTL 의미 갱신 |
| `docs/ARCHITECTURE.md` §5 | 승급 시퀀스 + 반환 이벤트 흐름 반영, `xrail.queue.active.size`/`releases.total` 메트릭 확정 |

> **train-service·common-lib 변경 없음(권장안 기준).** 반환 신호로 기존 `reservation.created`를 재사용하므로 새 이벤트/토픽/필드 불필요(CLAUDE.md §2 단순성). 만약 "보호 구역 이탈"을 예약 성공과 분리하고 싶어지면 그때 전용 이벤트 도입을 재검토.

---

## 7. 관측성 · 검증

**메트릭(추가/확정):**
- `xrail.queue.active.size{scope}` Gauge (문서엔 있으나 코드 미구현 — 이번에 실제 등록)
- `xrail.queue.releases.total{scope,reason}` Counter (reserved/left/expired)
- `xrail.queue.promote.skipped{scope}` Counter (자리 없어 승급 0인 tick)

**단위/통합 테스트(P8):**
- 승급 Lua: active가 maxActive면 승급 0 / 여유 k면 min(k,batch)만 / waiting 부족 시 있는 만큼.
- 반환 멱등: 동일 `reservation.created` 2회 → 슬롯 1회만 감소.
- ABA: 예약→재진입→재승급 후 지연 반환 도착 → 신규 슬롯 보존.
- 원자성: `@EmbeddedKafka` + 동시 enter 다발 → active ≤ maxActive (INV-1).
- E2E 시나리오: maxActive=5, waiting=20, 승급자 중 3명 예약/2명 no-show → 다음 tick 3자리만 채워지는지(=이전 성공만큼만 리필) 확인.

---

## 8. 롤아웃 순서

1. common-lib 변경 없음 → 재배포 불필요.
2. queue-service 먼저 배포 가능(반환 컨슈머·capacity 승급). **TTL/토큰 exp를 바꾸지 않으므로 train-service와 버전 동조 불필요**(Q5 위반 없음).
3. Redis 상태는 ephemeral(Q1) — 기존 active 항목은 TTL로 자연 소멸, 마이그레이션 불필요.
4. 배포 후 `active.size`가 maxActive를 상회하지 않는지, `promote.skipped`/`releases.total` 균형을 대시보드로 확인.

---

## 9. 미결 결정사항 (구현 착수 전 확정 필요)

- **D1. maxActive 실제 값:** DB 커넥션 풀(prod 100)과 정렬. maxActive=풀크기? 여유 마진? (§5.3 overcommit과 함께 결정)
- **D2. overcommit 도입 여부(phase-1):** no-show 완화를 phase-1에 넣을지, 순수 자리기반으로 먼저 내보내고 관측 후 튜닝할지.
- **D3. 반환 신호원:** `reservation.created` 재사용(권장) vs 전용 `queue.exited` 이벤트 신설.
- **D4. no-show TTL 값:** 600s 유지 vs "예약 세션 현실값"으로 조정(단, train-service 토큰 exp 동조 필수 — §5.4).
- **D5. 좌석조회 활동성 기반 조기 회수(§5.3 phase-2)를 로드맵에 넣을지.**
