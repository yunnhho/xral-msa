# XRail — 면접 예상 질문 답변

> 고동시성 열차 예매 MSA 플랫폼 (2026.05 ~ 2026.07)
>
> 이 문서는 별도로 정리된 "XRail 면접 예상 질문"(6개 주제 40여 문항)에 대한 답변을, **실제 코드베이스와 대조**해 작성한 것입니다.
> 설계 문서(README)와 코드가 어긋나는 지점, 검증이 부족한 지점은 방어하지 않고 **있는 그대로** 적었습니다.
> 인용한 파일 경로/메서드는 실제 구현 기준입니다.

---

## 0. 먼저 — 이 답변에서 정직하게 인정하는 것들 (요약)

포트폴리오를 잘 보이게 하는 것보다 정확한 인지가 중요하므로, 방어하기 어려운 항목을 앞에 모읍니다.

1. **"DB 비관적 락(`SELECT ... FOR UPDATE`)"는 실제로는 구현돼 있지 않다.**
   README §3.1 시퀀스 다이어그램과 §4.2는 "SELECT seat FOR UPDATE 비관적 락 더블체크"라고 적었지만, 실제 코드(`ReservationService.doCreate`)의 더블체크는
   `ticketRepository.existsByScheduleIdAndSeatId...StatusIn(...)` — **잠금 없는 존재 여부 조회**다. `@Lock(PESSIMISTIC_WRITE)`나 `FOR UPDATE`는 코드 어디에도 없다(grep 확인).
   → 즉 **동시성의 실제 직렬화 지점은 Redis Lua 비트마스크 단 하나**이고, DB 더블체크는 "Redis 비트가 유실됐을 때를 위한 2차 정합 확인"이지 동시성 락이 아니다. 이 점은 아래 1-8, 1-2에서 다시 정확히 설명한다.

2. **부하 수치(예약 p95 14ms · error 0%)는 단일 머신 + demo 오버라이드 환경의 수치다.**
   `docker-compose.demo.yml`에서 rate-limit off / MockPG 실패율 0 / bcrypt 10 / 계정 사전 시드로 맞춘, Apple Silicon 로컬 풀스택 7,000 샘플이다. 분산·다중 노드 환경의 수치가 아니다. (6-6에서 상술)

3. **스케줄러에 리더 선출·분산 락이 없다.** 모두 순수 `@Scheduled`이고 ShedLock 등 미도입(grep 확인). 다중 인스턴스로 뜨면 만료/재정합/Outbox-relay가 중복 실행된다. 큐 승급만 Lua 원자성으로 안전하다. (5-1, 6에서 상술)

4. **CAPTCHA는 stub**이다(실 프로바이더 미연동). README도 명시.

5. **서비스 간 네트워크는 mTLS/network policy 없이 docker-compose 네트워크로만 격리**돼 있다. Gateway를 우회한 내부 직접 호출은 헤더만 맞추면 통과한다. (3-1)

이 5가지를 전제로 개별 답변을 진행한다.

---

## 1. 좌석 점유 / 동시성 제어

### 1-1. Redis Lua 비트마스크를 선택한 이유는? Redisson 락만으로는 왜 부족했나

- 좌석 점유는 "좌석 하나"가 아니라 **"좌석 × 구간(segment)"** 단위다. 서울→대전 예매와 대전→부산 예매는 같은 좌석이라도 겹치지 않으므로 **동시에 팔려야 한다.** Redisson `RLock`은 "키 하나에 대한 상호 배제"라, 좌석 단위로 잠그면 이 구간 병렬 판매가 불가능해진다.
- 비트마스크는 `sch:{scheduleId}:seat:{seatId}` 키에 역 인덱스를 비트로 놓고 `[startIdx, endIdx-1]` 구간의 비트가 하나라도 1이면 충돌로 판정한다(`reserve_seat.lua`). GETBIT 검사 → 전부 0이면 SETBIT — 이 "검사와 set"이 원자적이어야 하는데, Redis single-thread + Lua 스크립트가 그 원자성을 공짜로 준다.
- 즉 선택 이유는 "락이 더 강해서"가 아니라 **점유 표현의 도메인 모델(구간)이 락이 아니라 비트 집합이기 때문**이다. Redisson으로 하려면 `RBitSet`을 쓰더라도 검사-set 사이를 다시 락으로 감싸야 하고, 그러면 왕복이 늘고 좌석 단위 직렬화로 회귀한다.
- 참고로 Redisson을 **버리진 않았다.** `LuaScriptService`는 Redisson `RScript`로 Lua를 실행하고, payment-service는 Redisson `RLock`을 멱등 처리에 쓴다. "적재적소"라는 표현이 정확하다.

### 1-2. 구간 단위 점유를 비트마스크로 어떻게 표현했나. 환승·다구간 예매는 어떻게 처리되나

- 표현: 노선의 역 순서(`RouteStation.stationSequence`)를 인덱스로 삼아, 출발역 idx ~ 도착역 idx-1 비트를 점유로 SETBIT. 예: 5역 노선에서 2→4 구간은 비트 [2,3]을 set.
- 가격도 이 구간 길이 기반이다(`calculatePrice = BASE_PRICE * (endIdx - startIdx) * seatCount`).
- **정직한 한계 — 환승/다구간은 지원하지 않는다.** `ReservationRequest`는 단일 `scheduleId` + 단일 `departureStationId/arrivalStationId`만 받는다. 한 예약은 **한 스케줄의 한 연속 구간**이다. "서울→부산을 대전 환승으로"처럼 스케줄 2개를 하나의 예약으로 묶는 흐름은 설계 범위 밖이고, 프론트에서 별도 예약 2건으로 만들 수밖에 없다. 비트마스크 자체는 여러 좌석(`seatIds` 리스트)을 한 트랜잭션에서 처리하지만, 이건 "다구간"이 아니라 "같은 구간의 다좌석"이다.

### 1-3. Redis가 죽으면 예매 전체가 멈추는데, 그 SPOF는 어떻게 볼 것인가

- 정직하게: **맞다. 예매 경로에서 Redis는 SPOF다.** 좌석 락(train), 큐 토큰(train interceptor), 큐 상태(queue-service는 Redis-only), 멱등 키까지 Redis에 의존한다. Redis가 죽으면 신규 예매는 사실상 불가하다.
- 다만 결이 다른 부분: Gateway의 rate-limit/브루트포스 카운터는 Redis 장애 시 **인메모리 fallback / fail-open**으로 degrade하도록 짜여 있다(`RateLimitFilter.applyLocalRateLimit`). 즉 "보안 필터는 열리는 방향으로 degrade, 예매 코어는 멈추는 방향"이다.
- 프로덕션이라면 답: Redis Sentinel/Cluster로 HA 구성 + AOF 영속화. 그리고 재시작 시 좌석 비트를 DB에서 복원하는 로직은 **이미 있다**(`ReconciliationScheduler.restoreOnStartup`, `@EventListener(ApplicationReadyEvent)` — 활성 티켓을 읽어 비트 재set). 이건 "Redis 데이터는 휘발 가능하고 DB가 진실"이라는 설계 의도를 코드로 뒷받침한다. 현재 프로젝트에는 Sentinel 미구성이므로, HA는 deferred로 명확히 인지하고 있다.

### 1-4. Lua 원자 연산 성공 후 DB 저장 전에 프로세스가 죽으면? (좌석 누수 시나리오)

- 시나리오: `reserve_seat.lua`로 비트는 set됐는데 그 뒤 `INSERT Reservation/Ticket` 커밋 전에 프로세스 kill → **Redis엔 점유 비트가 남고 DB엔 티켓이 없는 "phantom 비트"** = 좌석 누수.
- 방어 장치는 `ReconciliationScheduler.reconcile()`(5분). Redis 비트 중 DB 활성 티켓이 커버하지 않는 비트를 phantom으로 보고 해제한다.
- **핵심 안전장치 하나 더**: phantom을 **연속 2회 사이클 모두 phantom일 때만** 제거한다(`previousPhantomCandidates` 비교). 이유는 1-4의 역시나리오 — "정상 예매가 비트 set 후 아직 커밋 안 된 in-flight 상태"를 phantom으로 오인해 지우면 오히려 더블부킹이 난다. 이건 실제로 겪은 버그(README T-5)라 2회 확인으로 막았다.
- 비트 키에는 TTL 24h가 걸려 있어(`reserve_seat.lua`), 최악의 경우에도 하루 뒤엔 자연 소멸한다.

### 1-5. Reconciliation Scheduler가 필요하다는 건 앞 두 단계가 불완전하다는 뜻 아닌가. 실제로 스케줄러가 잡아낸 케이스가 있었나

- **맞다. 그리고 그게 이 설계의 핵심 인정이다.** Lua 원자성은 "Redis 안에서의 동시성"만 보장하지, "Redis와 DB라는 두 저장소 사이의 정합"은 보장 못 한다(1-4의 크래시, 이벤트 순서 역전 등). 그래서 3계층 중 3번째로 **비동기 재정합**을 둔 것이다. 즉 Reconciliation은 "1·2단계가 실패했을 때를 위한" 게 아니라 **"두 저장소를 쓰는 한 원리적으로 존재할 수밖에 없는 drift를 수렴시키는" 계층**이다.
- 실제로 잡아낸 케이스: 정직하게 — **프로덕션 트래픽으로 자연 발생한 케이스를 관측한 로그는 없다.** 대신 이 스케줄러의 존재 이유가 된 버그를 **개발 중 재현/수정**했다: (a) in-flight 예약을 phantom으로 오삭제(T-5), (b) 서비스 재시작 후 비트 유실 → `restoreOnStartup`으로 DB에서 복원. 즉 "스케줄러가 운영 중 X건을 잡았다"가 아니라 "스케줄러가 없으면 이러이러한 drift가 남는다는 걸 코드로 증명하고 대응했다"가 정확한 답이다.

### 1-6. Redisson 분산 락과 JPA `@Version` 낙관적 락을 함께 쓴 이유는? 중복 아닌가

- 이 둘이 함께 쓰이는 곳은 train이 아니라 **payment-service**다. 역할이 다르다:
  - **Redisson `RLock`** (`payment:lock:{idempotencyKey}`): "같은 Idempotency-Key로 동시에 들어온 결제 요청" 자체를 직렬화한다. `tryLock(0, 60s)` — 대기 없이 즉시 실패시켜 중복 결제 요청을 IDEMPOTENCY_CONFLICT로 튕긴다. **서비스 간/요청 간** 상호배제.
  - **JPA `@Version`**: 한 Payment 로우의 REQUESTED→COMPLETED 상태 전이가 다른 경로(예: 재시도, 컨슈머)와 경합할 때의 **로우 단위** lost-update 방지.
- 즉 "분산 요청 진입 차단(Redisson)" + "DB 로우 상태 전이 보호(@Version)"로 **레이어가 다르다.** 중복이라기보다, 락은 진입점을, 버전은 최종 커밋을 지키는 이중 그물이다. 다만 실무적으로는 "Idempotency-Key + DB unique(`payment.idempotency_key`) 조회"만으로도 상당 부분 커버되므로, **@Version이 실제로 경합을 막은 사례가 있었는지는 검증되지 않았다** — 방어적으로 넣은 쪽에 가깝다.

### 1-7. 오버부킹 0건을 "증명"한 방법은? 100 스레드는 검증 규모로 충분한가

- 증명 방법: `demo/01-oversell.sh` — 동일 schedule·seat·segment에 **100 스레드 동시** `POST /api/reservations`. 기대·실측 결과 = 201 정확히 1건 / 409 정확히 99건 / DB 티켓 1건 / Redis 비트 정합. JMeter 플랜(`xrail-overbook-test.jmx`)도 동일 시나리오.
- **정직한 한계**: "증명"이라 부르기엔 약하다. (a) 100 스레드는 단일 프로세스·단일 Redis에서 Lua 원자성을 태우는 검증이라, 원자성 특성상 통과가 거의 자명하다 — 즉 이 테스트는 "Lua가 원자적으로 동작함"을 보이지, "모든 동시성 경로가 안전함"을 보이지 않는다. (b) 진짜 리스크는 100 스레드 경합이 아니라 **Redis↔DB drift, 이벤트 순서 역전, 크래시 복구** 같은 경로인데 이건 부하 테스트로 커버 안 되고 개별 단위 테스트/수동 재현으로만 확인했다. (c) 규모로는 1,000까지도 의미가 크게 다르지 않다 — 원자 연산은 N에 선형이지 임계에서 깨지는 구조가 아니다. 그래서 "0건"은 **재현 가능한 데모**이지 **형식 증명**은 아니라고 답하는 게 정확하다.

### 1-8. Transactional 내 DB 존재 더블체크는 어떤 격리 수준에서 유효한가

- **가장 정확히 인지해야 할 질문.** 앞서 밝혔듯 이 더블체크는 `existsBy...`(비잠금 SELECT)이지 `FOR UPDATE`가 아니다. 따라서:
  - MySQL 기본 격리(REPEATABLE READ)든 READ COMMITTED든, **두 트랜잭션이 서로의 아직-커밋 안 된 INSERT를 못 본다.** 즉 이 존재 체크 **단독으로는** 두 동시 예매를 직렬화하지 못한다 — 둘 다 "없음"을 보고 둘 다 INSERT할 수 있다.
  - 그러므로 **동시성의 실제 방어선은 이 DB 체크가 아니라 그 앞의 Lua 비트마스크**다. Lua가 한쪽만 통과시키므로 애초에 두 번째 트랜잭션은 여기까지 못 온다.
  - 그럼 DB 더블체크는 왜 있나? → **"Redis 비트가 유실/불일치인데 DB엔 티켓이 있는" 경우를 잡기 위해서**다. 코드를 보면 dbConflict일 때 `luaScriptService.tryReserve(...)`로 **Redis 비트를 복원**하고 409를 던진다(`ReservationService.java:106-111`). 즉 이건 동시성 락이 아니라 **DB→Redis 방향의 자가 치유 + Redis 신뢰 불가 시 최후 정합 확인**이다.
  - 진짜로 이 체크를 동시성 락으로 만들려면 `@Lock(PESSIMISTIC_WRITE)` + unique 제약(`(schedule_id, seat_id, segment)` 유니크는 구간 겹침 때문에 단순 unique로는 불가)이 필요하다. 현재는 그렇게 돼 있지 않다. **README의 "비관적 락" 표기는 코드와 불일치하며, 이 문서가 그 괴리를 정정한다.**

---

## 2. 분산 트랜잭션 / 메시징

### 2-1. Choreography를 택한 이유와, Orchestration 대비 잃은 것은

- 택한 이유: 서비스 수(5)가 적고 흐름이 비교적 선형(예약→결제→확정/보상)이라, 중앙 오케스트레이터를 두면 그 자체가 모든 서비스를 아는 결합점이 된다. 각 서비스가 자기 관심 이벤트만 구독/발행하면 의존 방향이 단순해진다.
- 잃은 것(정직하게):
  1. **흐름 가시성.** "지금 이 예약이 사가의 어느 단계인지"가 코드 한 곳에 없다. 이걸 보완하려고 `reservation_saga_log`(OUTBOUND/INBOUND 기록)를 뒀지만, 이건 **사후 디버깅용**이지 흐름 제어용이 아니다(train CLAUDE.md T5 규칙).
  2. **타임아웃/보상 조율의 분산.** "20분 미결제 만료"는 스케줄러, "결제 실패 보상"은 컨슈머, "재정합"은 또 다른 스케줄러 — 보상 트리거가 여러 곳에 흩어진다.
  3. **테스트 난이도.** end-to-end 흐름을 검증하려면 Kafka를 띄워야 한다(`@EmbeddedKafka`).

### 2-2. 보상 책임을 train-service로 단일화했다면 그 서비스가 사실상 오케스트레이터 아닌가

- **날카로운 지적이고, 절반은 맞다.** train은 `payment.failed`/`payment.refunded`를 구독해 좌석을 되돌리고, 미결제 만료·재정합도 담당한다. "좌석 상태의 최종 책임자"라는 의미에서 **보상 로직이 train에 모여 있는 건 사실**이다.
- 다만 오케스트레이터와 다른 점: train은 **다른 서비스에게 "다음 단계를 하라"고 명령하지 않는다.** payment에게 "환불해"라고 직접 호출하는 게 아니라 `payment.refund-requested` 이벤트를 발행할 뿐이고, payment가 자기 판단으로 처리한다. 각 서비스는 여전히 자기 이벤트만 보고 반응한다. 즉 **"보상 책임의 집중"은 맞지만 "제어 흐름의 중앙집중"은 아니다** — train은 오케스트레이터가 아니라 "가장 많은 보상 책임을 진 참여자"다. 이 구분을 못 하면 비판이 성립하고, 하면 방어된다.

### 2-3. Transactional Outbox가 필요한 근본 이유(dual write 문제)를 설명해달라

- dual write 문제: "DB에 예약을 INSERT"와 "Kafka에 이벤트 발행"은 서로 다른 시스템이라 **하나의 원자 트랜잭션으로 묶을 수 없다.** DB 커밋 후 Kafka 발행 전에 죽으면 → 예약은 있는데 결제 서비스는 모름(이벤트 유실). 발행 후 DB 롤백이면 → 있지도 않은 예약에 대한 결제 시도(유령 이벤트).
- 해결: 이벤트를 Kafka로 바로 보내지 않고 **같은 DB 트랜잭션 안에서 `outbox_events` 테이블에 INSERT**한다(`OutboxRecorder.record`). 예약 INSERT와 outbox INSERT는 한 트랜잭션이므로 원자적. 실제 Kafka 발행은 별도 `OutboxRelayScheduler`(1초 폴링)가 PENDING 행을 읽어 발행하고 SENT로 마킹한다. 커밋된 것만 발행되므로 dual write가 사라진다.
- train·payment 둘 다 이 패턴이다(`train/OutboxRecorder`, `payment/OutboxRecorder`).

### 2-4. Outbox 릴레이 주기와 중복 발행은 어떻게 처리했나

- 주기: `train.outbox.relay-interval-ms:1000`(1초 `fixedDelay`), 배치 `findTop100ByStatusOrderByIdAsc(PENDING)`.
- 중복 발행 가능성: **있다, 그리고 의도적으로 허용한다(at-least-once).** "Kafka 발행 성공 → `markSent` UPDATE 커밋" 사이에 죽으면 다음 주기에 같은 행을 재발행한다. 그래서 **컨슈머 멱등이 전제**다 — 모든 이벤트에 `eventId(UUID)`가 있고, 컨슈머는 상태 가드(`status != PENDING` 등)와 `correlation_id` UNIQUE로 중복을 no-op 처리한다.
- **정직한 한계**: 릴레이 스케줄러도 리더 선출이 없다(2-9, 6 참조). train/payment 인스턴스가 여러 개면 **같은 PENDING 행을 여러 인스턴스가 동시에 집어 발행**할 수 있다(`findTop100...`에 `FOR UPDATE SKIP LOCKED`가 없음). 단일 인스턴스 가정으로만 안전하고, 이는 코드 주석에도 "단일 인스턴스 가정"으로 명시돼 있다. 다중화하려면 SKIP LOCKED 또는 샤딩이 필요하다.

### 2-5. DLT로 격리된 메시지는 이후 누가 어떻게 복구하나. 재처리 수단이 있나

- 현재 구현: `PaymentDltConsumer`가 `payment.completed.DLT` / `payment.refund-requested.DLT`를 구독해 **`DltAlertLog` 테이블에 적재 + ERROR 로그**만 남긴다. 예외를 다시 throw하지 않는다(DLT에서 또 실패하면 무한 루프이므로).
- **정직하게: 자동 재처리 수단은 없다.** DLT는 "격리 + 운영자 알림(특히 환불 실패는 금전 이슈라 라벨을 다르게)"까지고, 재처리는 **수동**이다 — `DltAlertLog`를 보고 운영자가 원인을 고친 뒤 재발행하는 방식을 상정한다. "누가 복구하나"에 대한 정직한 답은 "사람"이고, 자동 재드라이브(DLT→원토픽 리큐) 파이프라인은 미구현/deferred다.

### 2-6. 재시도 1초 간격 2회라는 수치의 근거는

- 코드: `new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2))` — 1초 간격 최대 2회 재시도 후 DLT.
- **정직한 근거**: 강한 실증 근거는 없다. 논리는 "일시적 장애(순간 DB 커넥션 부족, 리더 재선출 등)는 1~2초 안에 자주 회복되므로 짧게 2번만 주고, 그 이상은 영속적 결함으로 보고 빨리 격리해 재시도 폭풍을 막는다"이다. 값 자체는 **관측 기반 튜닝이 아니라 합리적 기본값**이다. 실전이라면 지수 백오프 + 지터, 그리고 실패 유형별(retryable vs non-retryable 예외 분류) 정책이 맞다 — 현재는 모든 예외를 동일 취급하는 단순 정책이다.

### 2-7. Kafka 파티션 키 설계는? 순서 보장이 필요한 단위는 무엇이었나

- 순서 보장이 필요한 단위 = **하나의 예약(reservationId)**. `reservation.created`→`payment.completed`→`seat.confirmed`가 같은 예약에 대해 순서대로 처리돼야 한다.
- 설계: `reservation.created`, `payment.completed`는 `partitions=3, key=reservationId`. 같은 reservationId는 같은 파티션 → 파티션 내 순서 보장으로 예약 단위 순서가 지켜진다. 순서가 무의미한 감사성 이벤트(`user.signed-up`, `payment.requested`)는 `partitions=1`.
- **정직한 보완**: 그럼에도 컨슈머는 순서 역전을 **가정하지 않는다.** `handlePaymentFailed`/`handlePaymentCompleted`에 상태 가드를 둬서, `payment.completed`가 `payment.failed`보다 먼저 오거나 중복돼도 안전하게 만들었다(README T-2). 즉 "파티션 키로 순서를 최대한 보장하되, 멱등·상태가드로 순서에 의존하지 않도록" 이중으로 짰다. 또한 실제 발행은 Outbox 릴레이를 거치므로, **outbox `id` 순서(ORDER BY id ASC)**가 파티션 순서의 실질적 근거다.

### 2-8. 멱등 컨슈머에서 correlation_id UNIQUE 위반 예외를 정상 처리로 넘기는 경계는 어디인가

- notification-service의 실제 버그이자 수정(README T-4)이 정확히 이 질문이다.
- 처음엔 단일 `@Transactional` 안에서 `NotificationLog`를 INSERT했다. 중복 이벤트로 `UNIQUE(correlation_id)` 위반이 나면 catch해도 **Hibernate가 트랜잭션을 rollback-only로 마킹** → 커밋 시점 `UnexpectedRollbackException` → Kafka 재시도 후 DLT. "catch해서 정상 처리"가 DB 레벨에서 안 먹혔다.
- 경계 정정: `NotificationLogWriter`를 분리해 채널별 INSERT를 **`@Transactional(REQUIRES_NEW)` 독립 트랜잭션**으로 격리. UNIQUE 위반은 그 자식 트랜잭션만 롤백하고, **상위 컨슈머 트랜잭션은 정상 커밋** → 오프셋도 정상 진행. 즉 "정상 처리로 넘기는 경계"는 **INSERT를 별도 물리 트랜잭션으로 떼어낸 지점**이다. 이걸 안 떼면 논리적으로 catch해도 물리적으로 롤백된다는 게 교훈이다. (Mockito mock 단위 테스트로는 이 rollback-only 마킹이 안 잡혀서, 실제 DB 통합 테스트로 회귀 검증했다.)

### 2-9. 5개 실패 시나리오는 무엇이었고 각각 어떤 보상 동작을 했나

README §3.3 보상 매트릭스 기준, 실제 코드 동작:

| # | 시나리오 | 트리거(코드) | 보상 동작 | 발행 이벤트 |
|---|---------|------------|----------|-----------|
| 1 | Lua 좌석락 부분 실패 | `doCreate` 동기 | 이미 잡은 비트 즉시 `rollback_seat.lua` 해제 → 409 | (이벤트 발행 전) |
| 2 | 결제 실패 | `handlePaymentFailed`(`payment.failed` 구독) | 티켓 cancel + Redis 비트 해제 + Reservation CANCELLED | `seat.released(PAYMENT_FAILED)` |
| 3 | 미결제 20분 타임아웃 | `ReservationExpiryScheduler`(60s) → `expireReservation` | 트랜잭션 내 재조회 후 비트 해제 + CANCELLED | `seat.released(TIMEOUT)` |
| 4 | 사용자/관리자 취소 | `cancelByUser`/`cancelByAdmin` | 비트 해제 + CANCELLED, 이미 PAID면 환불 사가도 | `seat.released(USER_CANCEL)` + `payment.refund-requested` |
| 5 | Redis↔DB 정합 깨짐 | `ReconciliationScheduler`(5m) | 연속 2회 phantom 비트만 해제 | `seat.released(RECONCILE)` |

정직한 포인트: #4의 "이미 PAID인 예약 취소 → 환불"은 코드에 있으나(`compensateAndCancel`의 `wasPaid` 분기 → `payment.refund-requested` 발행) MockPG 환불이라 실 PG 환불 정합까지 검증된 건 아니다.

---

## 3. 인증 / 게이트웨이

### 3-1. Gateway 단일 인증에서 내부 서비스로 직접 요청이 들어오면? (네트워크 격리·mTLS 여부)

- **정직하게: 현재 방어가 없다.** downstream 서비스는 `X-User-Id/Role/Name` 헤더를 무조건 신뢰하는데(자체 JWT 검증 없음, P6 원칙), Gateway를 우회해 train-service:8082로 직접 `X-User-Id: 1`을 넣어 호출하면 **그대로 통과한다.**
- 이게 안전한 유일한 근거는 **네트워크 격리** — docker-compose 내부 네트워크에서 8082 등은 외부로 publish하지 않고 Gateway(8080)만 노출한다는 가정이다. mTLS도, service mesh도, 인증 헤더 서명도 없다.
- 프로덕션 답: (a) mTLS 또는 mesh(Istio/Linkerd)로 서비스 간 상호 인증, (b) Gateway가 주입하는 헤더에 단명 HMAC 서명을 붙여 downstream이 검증, (c) K8s NetworkPolicy로 Gateway에서 온 트래픽만 허용. 현재는 (a)(b)(c) 전부 미구현이고, "경계 방어(Gateway) + 네트워크 격리"에만 의존한다는 걸 명확히 인지하고 있다.

### 3-2. X-User-* 헤더 주입 방식의 위험과, inbound 헤더 제거만으로 충분한가

- 위험: 클라이언트가 `X-User-Id`를 스푸핑해 타인 행세. 방어는 `HeaderStripFilter`(order -200, 가장 먼저)가 **inbound `X-User-*`를 무조건 제거** 후 `JwtValidationFilter`(-100)가 검증된 JWT에서 다시 주입한다.
- "제거만으로 충분한가" → **경계(Gateway)에서는 충분하지만 전체로는 불충분하다.** 3-1과 같은 문제: Gateway를 거치지 않는 경로가 있으면 strip 자체가 적용되지 않는다. 즉 이 필터는 "Gateway를 통과하는 트래픽"만 보증한다. 그래서 정확한 답은 "inbound strip은 Gateway 경계 스푸핑을 막는 필요조건이지, 내부 직접 접근까지 막는 충분조건은 아니다"이다.

### 3-3. Refresh 토큰 회전 체인에서 탈취된 이전 토큰이 재사용되면 어떻게 탐지하나

- 구현(`RefreshTokenService.rotate`): 정상 회전 시 이전 토큰을 `revoke()`(revokedAt set)한다. 탈취된 **이미 회전된(=revoked) 토큰**이 다시 오면 `old.isRevoked()`가 true → **해당 유저의 모든 refresh 토큰을 일괄 revoke + `blacklist:rt:{userId}` 세팅** 후 `TOKEN_REVOKED`. 이게 RTR(Refresh Token Rotation)의 재사용 탐지(reuse detection)다 — "이미 쓴 토큰이 또 왔다 = 체인 분기 = 탈취 의심 → 전체 무효화."
- **정직한 한계 2가지**:
  1. 탐지는 "탈취자와 정상 사용자 중 **둘째로 회전을 시도한 쪽**이 왔을 때" 발동한다. 탈취자가 먼저 회전하면 정상 사용자의 토큰이 revoked가 되어, 다음에 정상 사용자가 왔을 때 전체 무효화 → **정상 사용자도 강제 로그아웃**된다. 이건 RTR의 본질적 트레이드오프(안전 우선)라 의도된 동작이지만, "탈취자를 콕 집어 차단"하는 게 아니라 "둘 다 끊고 재로그인 요구"임은 분명히 인지해야 한다.
  2. 토큰 해시는 SHA-256으로 DB `token_hash`(unique) + Redis mirror에 저장한다. 탐지는 DB 조회 기반이라, DB가 진실이고 Redis는 캐시다.

### 3-4. Redis 블랙리스트가 유실되면 강제 무효화는 어떻게 되나

- 블랙리스트(`blacklist:rt:{userId}`)와 refresh mirror(`rt:{userId}`)는 Redis에 있지만, **진실은 DB `refresh_tokens.revoked_at`**이다. `rotate()`의 재사용 탐지는 DB의 `isRevoked()`로 판정하므로, **Redis 블랙리스트가 통째로 날아가도 refresh 회전 차단은 DB 기준으로 계속 작동**한다.
- 그럼 Redis 블랙리스트는 왜 있나 → **access 토큰(무상태, 30분)** 때문이다. access는 검증 시 DB를 안 보므로, 로그아웃/강제무효화 후에도 최대 30분간 유효하다. 블랙리스트는 이 access 창을 좁히는 용도인데, **정직하게: 현재 Gateway `JwtValidationFilter`는 이 블랙리스트를 조회하지 않는다**(access 검증은 순수 서명+만료만). 즉 블랙리스트는 refresh 계열 무효화 보조 신호로 쓰이고, **access 토큰 즉시 무효화는 사실상 "만료 대기(30분)"에 의존**한다. Redis 블랙리스트 유실의 실질 영향은 "이미 DB로 커버되는 부분이라 작다"이지만, 반대로 말하면 "access 즉시 강제 로그아웃은 원래도 완전하지 않다"가 정확한 인지다.

### 3-5. Access 토큰 만료 시간은 어떤 근거로 정했나

- 값: `JWT_ACCESS_TTL_MS:1800000` = **30분**, refresh 14일(`1209600000`).
- 근거: 3-4에서 밝혔듯 access는 무상태 검증이라 **탈취 시 만료까지 그대로 유효**하다. 그래서 짧을수록 안전하지만 너무 짧으면 refresh 왕복이 잦아진다. 30분은 "탈취 노출창을 30분으로 제한 + 사용자 경험(30분마다 조용히 회전) 절충"의 흔한 기본값이다. **엄밀한 정량 근거(위협 모델·재발급 부하 측정)로 도출한 값은 아니고, 업계 관행 기반 기본값**이라고 답하는 게 정직하다.

### 3-6. 비회원 accessCode 인증의 브루트포스 내성은

- 구조: 비회원 로그인은 `accessCode(10자, 62진 알파벳) + phone + password` **3요소 동시 일치**를 요구한다(`NonMemberService.login`의 `findByAccessCodeAndPhone` + `passwordEncoder.matches`).
- 내성:
  - accessCode 공간 = 62^10 ≈ 8.4×10^17. `SecureRandom` 생성이라 예측 불가.
  - Gateway `BruteForceFilter`가 `/api/auth/non-member/login`을 커버 — IP+경로별 1분 5회 실패 시 5분 IP 차단(429).
  - RateLimitFilter의 `auth-login` 버킷(10/min)도 중첩 적용.
- **정직한 한계**: (a) IP 기반 차단이라 분산 IP(봇넷)엔 약하다. (b) accessCode+phone을 알면(예: 예매 확인 화면 유출) password(4~6자리 숫자)만 남는데, 숫자 6자리는 공간이 작아 rate-limit이 유일한 방벽이다. (c) 계정 잠금(계정 단위 실패 카운트)이 아니라 IP 단위라, 같은 비회원을 여러 IP로 노리면 막지 못한다. 실전이라면 accessCode+phone 단위 실패 카운터가 필요하다.

### 3-7. OAuth2 카카오/네이버 연동에서 동일 이메일 계정 병합은 어떻게 처리했나

- 구현(`CustomOAuth2UserService`): ① `findBySocialProviderAndSocialId`로 기존 소셜 계정 조회 → 없으면 ② `createMember`에서 **이메일로 기존 회원 조회 → 있으면 `updateSocial(provider, providerId)`로 그 계정에 소셜 정보를 붙여 병합**, 없으면 신규 생성.
- **정직한 보안 위험 — 이건 실무에서 문제 될 부분이다**:
  1. **이메일 소유 검증 없이 병합**한다. 카카오/네이버가 준 이메일을 신뢰하는데, 만약 로컬 회원가입으로 `victim@x.com` 계정이 이미 있고 공격자가 그 이메일로 소셜 계정을 만들 수 있다면, 소셜 로그인만으로 victim 계정에 소셜 인증 경로가 붙어 **계정 탈취**가 가능하다. 최소한 "이메일 verified 여부 확인" 또는 "병합 전 기존 계정 소유 확인(비번 재입력/이메일 인증)"이 필요하다.
  2. 카카오는 이메일 제공이 선택 동의라 **null일 수 있다.** 코드도 `email != null` 가드가 있어 이메일 없으면 무조건 신규 생성 → 같은 사람이 이메일 미동의로 재로그인하면 socialId로는 찾히니 괜찮지만, provider가 바뀌면 별도 계정이 된다.
  3. 동시 최초 로그인 2건이 경합하면 둘 다 신규 생성될 수 있다(unique 제약이 최종 방어).
- 요약: "동일 이메일 자동 병합"은 UX엔 편하지만 **이메일 소유 검증이 빠진 상태의 병합이라 취약**하다는 걸 인지하고 있고, 이건 명확한 개선 대상이다.

---

## 4. MSA 구조 설계

### 4-1. 개인 프로젝트에 MSA가 정말 필요했나. 모놀리스 대비 얻은 것과 치른 비용은

- **가장 자기비판적으로 답해야 하는 질문이고, 정직한 답은 "기능 요구만 보면 필요 없었다"이다.** 이 도메인·트래픽 규모라면 모듈러 모놀리스로 충분하고, 더 빠르고 안전했을 것이다.
- 얻은 것(학습 목적 프로젝트로서): Database per service, Saga choreography, Outbox, 분산 트레이싱, Gateway 인증 분리 같은 **MSA 특유의 문제를 실제로 부딪히고 해결한 경험**. README 명시대로 "포트폴리오"가 목적이다.
- 치른 비용(정직하게):
  - **분산 트랜잭션 복잡도**: 모놀리스면 `@Transactional` 하나로 끝날 예약-결제-좌석확정이, Outbox+Kafka+보상 사가+멱등+DLT+재정합으로 폭발했다. 실제 버그(README T-2~T-5) 대부분이 이 경계에서 나왔다.
  - **정합성 저하**: 강한 일관성 → 결과적 일관성. drift를 수렴시키는 스케줄러가 추가로 필요해졌다.
  - **운영 부담**: 15개 컨테이너, 서비스별 DB/Redis DB 인덱스, Eureka.
- 좋은 답의 태도: "MSA가 정답이라서가 아니라 **MSA의 문제를 학습하려고** 택했고, 그 대가(복잡도·정합성)를 코드로 체감했다. 실서비스라면 모듈러 모놀리스에서 시작해 병목이 증명된 부분만 분리했을 것"이라고 인정하는 것.

### 4-2. 스냅샷 비정규화 데이터가 원본과 어긋나면 어떻게 되나

- 예: `Reservation.userName`은 auth의 이름을 예약 시점에 스냅샷한 값이다. 이후 사용자가 개명하면 **예약에 박힌 이름은 옛 이름으로 남는다.**
- 이게 **버그가 아니라 의도**인 경우가 많다 — "예매 당시의 이름/좌석/가격"은 그 시점 사실로 보존돼야 하는 성격이다(영수증 성격). 그래서 어긋남이 곧 오류는 아니다.
- 하지만 "현재값을 보여줘야 하는" 화면에서는 stale해진다. 현재 코드는 **이 스냅샷을 갱신하는 이벤트 구독이 없다**(auth의 프로필 변경 → train으로 전파 없음). 즉 어긋나면 **그대로 방치**되며, 이건 인지된 미구현이다. 필요하면 `user.updated` 이벤트로 스냅샷을 갱신하거나, 표시 시점에 auth를 조회하는 방식이 필요하다.

### 4-3. 크로스 서비스 FK를 제거한 뒤 참조 무결성은 무엇으로 보장했나

- 정직하게: **DB 레벨 참조 무결성은 보장하지 않는다.** train의 `user_id`는 그냥 `Long` 컬럼이고 auth를 가리키는 FK가 없다. auth에서 유저가 지워져도 train은 모른다.
- 대신 **애플리케이션 레벨의 결과적 정합**으로 대체한다: (a) userId는 Gateway가 검증한 JWT에서 온 값이라 "존재하는 유저"임이 보장되고, (b) 도메인 이벤트(사가)로 상태를 전파하며, (c) 스냅샷으로 조회 시 조인이 필요 없게 만든다.
- 즉 "참조 무결성"을 "이벤트 기반 결과적 정합 + 신뢰 가능한 userId 전파"로 **바꾼** 것이지 유지한 게 아니다. 이건 Database per service의 필연적 대가다.

### 4-4. 서비스 간 조회가 필요한 화면(예: 예매 내역 + 열차 정보)은 어떻게 조립했나

- 원칙: 조인 대신 **스냅샷 비정규화**. 예매 티켓에 이미 `scheduleId, seatId, startStationId/Idx, endStationId/Idx, price`가 박혀 있어(`Ticket` 엔티티) 열차/구간/가격 정보를 train 내부에서 자족적으로 응답한다(`ReservationResponse`). 좌석 번호도 train의 `Seat`에서 조회.
- **정직한 한계**: 진짜 크로스 서비스 조합(예: 예매 내역 + 결제 상태 + 알림 이력)이 한 화면에 필요하면 현재는 **프론트가 여러 API를 각각 호출해 합치는** 구조다. BFF나 API composition 레이어는 없다. 그래서 "서비스 간 조회"는 대부분 **스냅샷으로 회피**했고, 회피 못 하는 건 클라이언트 조합으로 넘겼다.

### 4-5. 서비스 경계를 이 5개로 나눈 기준은

- 기준: **트랜잭션 경계 + 데이터 소유권 + 확장 특성**.
  - auth: 인증/계정 — 보안 격리, 다른 도메인과 생명주기 다름.
  - train: 예약/좌석/스케줄 — 동시성 핵심, 이 프로젝트의 코어.
  - queue: 대기열 — **Redis-only, DB 없음**, 순간 트래픽을 흡수하는 버퍼라 상태 특성이 완전히 다름.
  - payment: 결제 — 금전, 멱등/정합 요구가 특별하고 PG 연동 격리.
  - notification: 알림 — 순수 소비자, 실패해도 본류에 영향 없어야 함(비동기 격리).
- **정직한 자기비판**: 이 규모에서 queue와 train은 사실 강하게 붙어 있다(큐 토큰 검증을 train이 함). notification은 서비스로 뗄 만큼 무겁지 않아 "이벤트 소비 모듈"로도 충분했다. 즉 경계 기준 자체는 합리적이지만, **일부는 학습을 위해 다소 과분할**한 면이 있다(4-1과 같은 맥락).

### 4-6. Resilience4j는 어떤 지점에 적용했고, 임계값은 어떻게 정했나

- 적용 지점: **Gateway의 각 downstream 라우트**에 CircuitBreaker(`*-cb`). 설정(`api-gateway/application.yml`): `slidingWindowSize=10, failureRateThreshold=50%, waitDurationInOpenState=10s, halfOpen 3콜`. TimeLimiter는 기본 `5s`, 단 **queue-service만 600s**(SSE는 장수명 커넥션이라 5초로 끊으면 안 됨).
- 임계값 근거(정직하게): **관측 기반 튜닝이 아니라 합리적 기본값**이다. 50% 실패율/윈도우 10은 Resilience4j 관례적 출발값이다. 예외는 TimeLimiter 5s인데, 이건 **실제로 사고를 냈다** — 부하 테스트 run1에서 bcrypt strength 12가 로그인 해싱을 느리게 해 5s TimeLimiter를 넘겨 Circuit이 열리고 503 연쇄가 났다(README/6-2). 즉 "5s"라는 값은 근거 있게 정한 게 아니라 **부하 테스트가 그 값의 부작용을 드러냈고, 원인(bcrypt)을 고쳐 대응**했다. queue 600s만은 "SSE 특성상 5s면 무조건 끊긴다"는 명확한 근거로 오버라이드했다.

---

## 5. 대기열 / 입장 제어

### 5-1. 3초 주기 스케줄러가 여러 인스턴스로 뜨면 중복 승급이 나지 않나 (리더 선출 여부)

- **리더 선출은 없다.** 하지만 이 케이스는 **Lua 원자성으로 안전**하다. `QueueScheduler.tick()`이 여러 인스턴스에서 동시에 돌아도, 실제 승급은 `promote.lua` 한 스크립트가 담당하고 그 안에서 **`ZREMRANGEBYSCORE`(만료 정리)→`ZCARD`(현재 active)→`capacity = maxActive - active`만큼만 `ZRANGE`+`ZADD`+`ZREM`**을 원자적으로 한다. Redis single-thread라 두 인스턴스의 promote.lua는 **직렬화**되고, 두 번째 실행은 이미 채워진 active를 보고 capacity를 다시 계산하므로 **max-active(100) 상한을 넘지 않는다.**
- 즉 "중복 승급으로 인한 over-admission은 없다." 다만 다중 인스턴스면 (a) rank 업데이트 SSE가 중복 전송될 수 있고(무해), (b) promote.lua 호출 횟수가 늘어 Redis 부하가 소폭 는다. 정직하게: **"승급의 정확성은 Lua가, 스케줄러 다중화 안전성도 Lua가 사실상 커버"**한다. 이건 5-1의 좋은 방어 포인트다. 반면 train의 만료/재정합/outbox 스케줄러는 이런 원자 보호가 없어 다중화가 위험하다(6 참조) — 서비스마다 안전성이 다르다는 걸 구분해서 답하는 게 정확하다.

### 5-2. 사용자가 브라우저를 그냥 닫으면 슬롯은 언제 반환되나

- 3가지 반환 경로(`docs/QUEUE_ADMISSION_REDESIGN.md` §4): ① `reservation.created` 수신 시 조기 반환(`release.lua`), ② `POST /leave` 명시 반환, ③ **active-set score의 TTL(600s) 만료 폴백**.
- 브라우저를 그냥 닫으면 ①②가 안 일어나므로 **③ TTL 만료 = 최대 600초(10분) 후 자동 반환**된다. active-set은 `ZADD ... score=expiresAt`이고 promote.lua가 매번 `ZREMRANGEBYSCORE 0, now`로 만료분을 청소하므로, 다음 승급 사이클에 자연 회수된다.
- **정직한 함정(문서에 스스로 기록된 것)**: 이 TTL 폴백이 유일한 no-show 회수 수단인데, `enter()` 재발급이 TTL을 매번 리셋하면 폴백이 무력화된다(§4.6, INV-3 붕괴). 그래서 **세션 절대 상한(`session-cap-seconds`, first-키)**을 둬서 "재발급 폴링만으로 슬롯 영구 보유"를 차단했다. 즉 "브라우저 닫음 → 10분 후 회수"가 성립하려면 재발급 무한연장 차단이 전제고, 그걸 설계에 반영했다.

### 5-3. SSE 커넥션이 수천 개일 때의 리소스 문제는 어떻게 보나

- 구조: `SseEmitterRegistry`가 `ConcurrentMap<scope, Map<userId, SseEmitter>>`로 emitter를 들고, `HeartbeatScheduler`가 25초마다 전체를 순회하며 heartbeat를 쏜다.
- 리소스 관점(정직하게): SSE는 커넥션당 스레드를 점유하진 않지만(서블릿 비동기), **커넥션당 힙(emitter 객체) + 25초 heartbeat의 O(N) 순회 + rank 업데이트의 O(N) 순회(3초마다)**가 있다. 수천이면 3초마다 수천 번 `emitter.send`가 돈다. 톰캣 `maxConnections`/파일디스크립터도 상한이다.
- 완화 요소: heartbeat/rank 전송 실패 시 즉시 registry에서 제거해 죽은 커넥션이 쌓이지 않게 한다. 인스턴스 간에는 Redis RTopic pub/sub으로 이벤트를 퍼뜨리고 구독자 없으면 로컬 폴백(수평 확장 대비).
- **한계**: 진짜 수만~수십만 동접이면 이 in-memory emitter 모델은 인스턴스 메모리·순회 비용에서 한계다. 실전이라면 SSE 게이트웨이 분리, 커넥션 샤딩, 또는 롱폴링/웹소켓 게이트웨이 앞단이 필요하다. 현재는 "동시 active 100 상한"이 있어 **실제로 활성 SSE가 폭증하는 구조는 아니지만**(대기자 SSE는 많을 수 있음), 대규모 대기자 SSE 자체의 비용은 미해결로 인지한다.

### 5-4. SSE와 Polling Fallback의 전환 판단 기준은

- 프론트(`useQueueStatus` 훅) 기준: **EventSource가 2회 연속 실패**하면 2초 주기 `GET /api/queue/status` 폴링으로 전환한다(README §3.4). 페이로드는 SSE와 동일해 화면 로직은 그대로다.
- 판단 기준의 근거: "네트워크/프록시가 SSE를 못 유지하는 환경(일부 기업 프록시, 모바일)에서도 대기열이 동작해야 한다"는 견고성 우선. 2회는 "일시적 끊김 한 번엔 폴링으로 안 내려가고, 반복되면 내려간다"는 최소 임계다. **정직하게: 2회라는 값 자체는 실측 튜닝이 아니라 합리적 최소값**이다.

### 5-5. 평시 우회/포화 시 자동 대기의 전환 임계값은 어떻게 정했나

- 동작: 대기열 진입(`POST /api/queue/token`) 시 현재 active가 상한 미만이면 즉시 입장(우회), 포화면 대기(waiting ZSET). promote.lua의 `capacity = maxActive - activeCount` 계산이 이 경계다.
- 임계값 = **max-active 100** 하나가 사실상 전환점이다. active < 100이면 즉시 승급, =100이면 대기. 별도의 "평시/포화" 이중 임계(히스테리시스)는 없다.
- **정직한 한계**: 단일 임계라 경계에서 진동(100 근처에서 즉시입장↔대기가 요동)할 수 있다. 히스테리시스나 부하 기반 동적 상한은 미구현이다. FORCE_ON/AUTO 토글은 운영자가 대기열을 강제 온/오프하는 수동 스위치이지, 자동 전환 임계는 아니다.

### 5-6. 동시 active 100명이라는 상한의 근거는

- **정직하게: 데모/단일 머신 기준으로 "예매 코어가 무리 없이 처리 가능한 동시 예매자 수"로 잡은 값이지, 용량 산정(capacity planning)으로 도출한 값은 아니다.** 100명이 동시에 좌석 조회+예약+결제를 해도 train의 HikariCP(30)·Redis·DB가 버티는 선에서 임의로 정했다.
- 실전이라면 이 값은 **"보호하려는 하류 자원의 처리량"에서 역산**해야 한다 — 예: 예약 트랜잭션 평균 지연 × 목표 처리량 = 동시 허용치. 현재는 설정값(`max-active`)으로 빼놨을 뿐(하드코딩 아님) 근거 있는 산정은 아니다. 이걸 "대기열의 목적은 하류 보호이고, 상한은 하류 용량에서 나와야 한다"고 인지하고 있는 것 자체가 답의 핵심이다.

---

## 6. 성능 / 운영

### 6-1. 고정 윈도우 Rate Limit의 경계 버스트 문제를 알고 있나. 왜 슬라이딩을 안 썼나

- **안다.** 고정 윈도우(`rate:{ip}:{bucket}:{minute_epoch}`, TTL 60s)는 분 경계에서 버스트를 허용한다 — 예: 12:00:59에 한도만큼 + 12:01:00에 또 한도만큼 → 순간적으로 2배가 통과한다.
- 왜 슬라이딩을 안 썼나(정직하게): (a) 구현 단순성 — 고정 윈도우는 `INCR`+`EXPIRE` Lua 2줄로 끝나고 인메모리 폴백도 쉽다. (b) 이 프로젝트에서 rate-limit의 목적은 정밀 QoS가 아니라 **매크로/브루트포스 억제**라, 경계 버스트로 잠깐 2배가 새도 치명적이지 않다고 판단했다. (c) 정밀이 필요한 브루트포스 로그인은 별도 `BruteForceFilter`(실패 카운터+차단)로 이중 방어한다.
- 슬라이딩이 필요하면: 슬라이딩 윈도우 로그(ZSET에 타임스탬프)나 토큰 버킷으로 바꾸는 게 정석이고, Redis Lua로 어렵지 않다. **의식적으로 단순성을 택한 트레이드오프**이지 몰라서 안 쓴 게 아니라는 게 답의 핵심.

### 6-2. 에러율 56%의 원인 3가지를 어떤 순서로, 어떤 근거로 진단했나

`docs/problem.md` 기준, **부하 테스트를 반복(run1~4)하며 큰 원인부터 벗겨낸** 순서:

1. **run1, 에러 56% — bcrypt strength 12로 인증 붕괴**: 1,000명 가입+로그인 = 해싱 ~2,000회 ≈ 540 코어-초 → Gateway TimeLimiter 5s 초과 → CircuitBreaker 오픈 → 503 연쇄. 근거: 에러가 로그인 구간에 집중 + CPU 포화 관측. 대응: bcrypt strength를 프로퍼티화(하한 10 코드 강제) + **가입은 측정 시나리오에서 분리(사전 시드)** — PRD의 측정 정의(search→reserve→pay)와 정렬.
2. **run2, 에러 18% — 좌석 조회가 비트당 Redis 왕복**: `isFree()`가 좌석×구간마다 GETBIT → 검색 1회에 최대 ~2,000 왕복, 그동안 `@Transactional`이 DB 커넥션 점유 → HikariCP(30) 고갈 → 30s 대기 → 503. 근거: 느린 트랜잭션 + 풀 고갈 메트릭. 대응: **배치 Lua `check_free_batch.lua`로 스케줄당 1왕복** → 검색 p95 **4초→21ms**.
3. **run3·4, 401 결정적 재현 — 소비된 큐 토큰 재반환**(6-5): 근거는 "매 런 같은 시간대(램프업 오프셋과 1:1)에 401" → 이전 런의 실패 사용자 버킷(TTL 600s)이 원인임을 타임라인 역추적으로 규명.
- 진단 태도의 핵심: **"큰 원인 1개를 고치면 다음 원인이 드러난다"**는 걸 알고 반복 측정했다는 점, 그리고 각 원인을 "메트릭(CPU/풀/지연) + 실패의 시간적 패턴"으로 좁혔다는 점. "56%"라는 숫자보다 이 진단 프로세스가 답의 알맹이다.

### 6-3. bcrypt CPU 포화는 어떻게 해결했나. cost factor를 낮췄다면 보안 트레이드오프는

- 해결: strength 12 → **10**으로 낮추되, **코드에서 하한 10을 강제**(그 아래로는 설정 못 하게)했다. 그리고 측정 시나리오에서 대량 회원가입을 빼고 계정을 사전 시드했다(가입 해싱 폭주는 테스트 아티팩트이지 실사용 패턴이 아니므로).
- 트레이드오프(정직하게): bcrypt cost는 로그 스케일이라 12→10은 **해시당 비용이 약 1/4**로 준다. 즉 오프라인 크래킹 저항이 그만큼 낮아진다. 다만 **10은 OWASP가 제시하는 하한선**이고, 여전히 해시당 수십 ms라 실무 허용 범위다. "무한정 낮추는" 게 아니라 "하한 10을 코드로 못박아 안전선을 유지"한 게 핵심. 근본적으로는, 로그인 경로의 CPU는 cost를 낮추기보다 **인증 서비스 수평 확장 + 해싱 부하 격리**로 푸는 게 맞고, cost 하향은 데모/단일머신 제약에 대한 실용 타협임을 인지한다.

### 6-4. HikariCP 풀 사이즈를 정한 근거는

- 값: 로컬 **max 30**(`auth`/`train` 확인), 문서상 prod 100(`docs/ARCHITECTURE.md` §; PRD도 dev 30/prod 100).
- 근거(정직하게): 강한 정량 근거는 없다. 30은 "단일 머신 로컬에서 MySQL 커넥션·CPU를 과하게 안 먹으면서 동시 예약 트랜잭션을 받는" 실용값이다. 오히려 이 값의 의미는 **6-2 run2에서 드러났다** — 느린 트랜잭션(좌석 조회 왕복)이 30개 커넥션을 다 물어 고갈됐고, 그건 "풀이 작아서"가 아니라 "트랜잭션이 길어서"였다. 그래서 **풀을 키우는 대신 트랜잭션을 짧게(배치 Lua) 만드는 쪽으로 해결**했다.
- 정석 산정: `pool = Tn × (Cm - 1) + 1` 류의 공식이나 "코어 수 × 2 + 유효 스핀들" 같은 가이드가 있지만, 이 프로젝트는 그걸로 도출한 게 아니라 **관행값 + 부하 테스트로 병목의 진짜 원인(트랜잭션 길이)을 찾은** 케이스다. "풀 사이즈는 튜닝 대상이지만, 대개 진짜 문제는 트랜잭션 길이"라는 인식이 답의 핵심.

### 6-5. 소비된 큐 토큰 재발급 버그의 원인은 무엇이었나

- 원인(`docs/problem.md` run3·4): `QueueService.enter()`가 기존 active 버킷에 든 **이미 소비된 HMAC 토큰을 그대로 재반환**했다. 큐 토큰은 `HMAC(userId:scope:issuedAt)`로 **결정적**이라, 같은 issuedAt으로 재발급하면 예전에 `queue:token:used:*`로 소비 처리된 토큰이 그대로 되살아난다 → 재예매 시 인터셉터가 "이미 사용됨"으로 401.
- 진단 근거: 실패가 **매 런 동일 시간대(램프업 오프셋과 1:1 대응)**에 결정적으로 재현 → "이전 런에서 실패한 사용자의 active 버킷(TTL 600s)이 다음 런까지 살아남아 소비된 토큰을 재반환"임을 타임라인 역추적으로 규명.
- 수정: `enter()`에서 **신규 토큰 재발급**(새 issuedAt). 프론트엔드에서 우회로만 안 드러나던 잠복 버그를 부하 테스트가 서버측에서 잡아낸 사례.
- **정직한 후속(문서에 기록됨)**: 이 "재발급" 수정이 이번엔 **무한 슬롯 연장** 문제를 열었다(`QUEUE_ADMISSION_REDESIGN.md` §4.6, 표 5.15) — 재발급이 TTL·score를 매번 리셋하면 폴링만으로 슬롯을 영구 보유. 그래서 **세션 절대 상한(first-키)**으로 다시 막았다. 하나의 수정이 다음 결함을 드러낸, 정직하게 보여줄 만한 반복 개선 체인이다.

### 6-6. p95 14ms는 어떤 환경(단일 머신 여부, 데이터 규모)에서 측정한 수치인가

- **정직하게 전부 공개**: 2026-07-04, **단일 Apple Silicon 로컬 머신**에 풀스택(15컨테이너)을 띄우고 JMeter로 측정. **demo 오버라이드** 적용 — `rate-limit.enabled=false`(단일 IP 부하기라 IP 리미터와 양립 불가), `PAYMENT_MOCK_FAILURE_RATE=0`, `bcrypt strength=10`, **계정 1,000개 사전 시드**(가입 해싱을 측정에서 제외). 샘플 7,000.
- 그 조건에서 A(E2E 7단계) p50 6ms/p95 67ms, **A-1(예약 생성 단계만) p95 14ms**, error 0%, overbooking 0.
- 이 수치의 정확한 의미: "**단일 머신·in-memory 근접·이상적 조건에서 예약 생성 코드경로 자체가 빠르다**"는 것이지, "1,000 동접 프로덕션에서 14ms"가 아니다. 네트워크 홉, 실 PG 지연, rate-limit, 실계정 로그인 해싱, 다중 노드 조정이 모두 빠져 있다. **재현 조건을 이렇게 명시하는 것 자체가 이 수치를 정직하게 다루는 방법**이고, 부풀리지 않는 게 중요하다.

### 6-7. 커스텀 메트릭 12종 중 실제로 알람을 건 지표는 무엇인가

- 정직하게: **Prometheus Alertmanager 룰로 실제 알람을 건 지표는 없다.** 12종(좌석 락 충돌률, 결제 성공/실패, 대기열 크기, 보상 발동 횟수, outbox 발행 수, 재발급 상한 도달 등)은 **Micrometer로 노출 + Grafana 대시보드(5패널)로 시각화**까지가 구현 범위다. 자동 알람(임계 초과 시 통지)은 미구성이다.
- "알람을 건다면" 후보: (a) `xrail.train.outbox` PENDING 적체(릴레이 실패 = 이벤트 지연), (b) DLT 적재(`DltAlertLog`, 특히 환불 실패), (c) 좌석 락 충돌률 급등(경합/어뷰징), (d) 대기열 크기 폭증. 이 중 **(b) DLT는 코드가 이미 "운영자 즉시 확인" 라벨을 달아** 사실상 알람 대상으로 설계돼 있다(단 통지 채널 연결은 안 됨). "관측은 갖췄고 알림 자동화는 deferred"가 정확한 답이다.

### 6-8. Zipkin trace로 실제 개선한 지점이 있었나

- 정직하게: **"Zipkin trace를 보고 X를 고쳤다"는 명시적 기록은 없다.** 6-2의 3대 원인은 주로 에러율/CPU/풀 메트릭과 실패의 시간 패턴으로 진단했다.
- 다만 Zipkin이 기여한 지점: 6개 서비스 전 구간(HTTP 자동 + Kafka `brave-instrumentation-kafka-clients`) 트레이스가 있어, **"예약→결제→확정" 사가가 어느 서비스/어느 홉에서 지연되는지**를 눈으로 확인하는 데 썼다. 특히 run2의 좌석 조회 지연이 train 트랜잭션 구간에 몰린 것을 트레이스로 교차 확인했다. 즉 **1차 진단 도구는 메트릭, 트레이스는 "어디서 시간이 갔나"를 병목 국소화하는 보조**였다고 답하는 게 정직하다. "trace 없었으면 못 고쳤다"는 과장은 하지 않는다.

---

## 7. 답변 준비 우선순위 (원본 §7 대응)

원본이 지목한 4개 최우선 항목에 대한 압축 정리:

1. **부하 테스트 수치의 재현 조건** → 6-6. 단일 Apple Silicon + demo 오버라이드(rate-limit off/mock-fail 0/bcrypt 10/계정 사전시드) + 7,000 샘플. 예약 생성 코드경로 p95 14ms의 의미와 한계를 명확히.
2. **3중 안전망의 각 계층이 담당하는 실패 케이스 구분** → 1-4·1-5·1-8. ① Lua = Redis 내 동시성(실제 직렬화 지점), ② DB 더블체크 = **락이 아니라** Redis 유실 시 DB→Redis 자가치유, ③ Reconciliation = 두 저장소 간 drift 수렴. **"비관적 락"은 문서 표기일 뿐 코드엔 없음**을 정직하게.
3. **Choreography 선택 근거와 한계** → 2-1·2-2. 서비스 소수·선형 흐름이라 택했고, 흐름 가시성/보상 분산을 잃었으며 saga_log로 보완. train은 "보상 책임 집중"이지 "제어 중앙집중(오케스트레이터)"은 아님.
4. **MSA 채택에 대한 자기 비판** → 4-1. "기능만 보면 불필요했고, MSA의 문제를 학습하려 택했으며, 분산 트랜잭션 복잡도·정합성 저하라는 대가를 코드로 체감했다. 실서비스면 모듈러 모놀리스에서 시작했을 것."

---

## 부록. 코드-문서 괴리 및 개선 백로그 (정직 목록)

면접에서 "개선점"을 물으면 그대로 쓸 수 있는, 실제로 확인된 항목:

| # | 항목 | 상태 |
|---|------|------|
| A1 | README §3.1/§4.2의 "SELECT FOR UPDATE 비관적 락"이 코드엔 없음(`existsBy` 비잠금 조회) | **문서-코드 불일치, 정정 필요** |
| A2 | Outbox 릴레이·만료·재정합 스케줄러에 리더 선출/`SKIP LOCKED` 없음 — 다중 인스턴스 시 중복 처리 | 단일 인스턴스 가정, 다중화 시 개선 필요 |
| A3 | OAuth2 이메일 병합에 이메일 소유 검증 없음 | 계정 탈취 벡터, 개선 필요 |
| A4 | access 토큰 즉시 무효화가 사실상 만료(30분) 대기에 의존(Gateway가 블랙리스트 미조회) | 인지된 한계 |
| A5 | DLT 자동 재처리 파이프라인 없음(수동 복구) | deferred |
| A6 | 커스텀 메트릭에 자동 알람 룰 없음 | 관측만 구현, 알림 deferred |
| A7 | 환승/다구간 예매 미지원(단일 스케줄·단일 구간) | 설계 범위 밖 |
| A8 | 서비스 간 mTLS/network policy 없음(경계+네트워크 격리에만 의존) | deferred |
| A9 | @Version이 실제 경합을 막은 사례 미검증(방어적 추가) | 검증 부족 |
| A10 | 대기열 스냅샷/스케줄러 임계값(100, 3s, 2회 등) 상당수가 관행값·미튜닝 | 근거 보강 여지 |
