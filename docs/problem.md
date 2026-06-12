# XRail MSA — 포트폴리오 / 이력서 자료집

> 고동시성 기차 예매 플랫폼을 **Spring Cloud 기반 MSA**로 설계·구현한 1인 프로젝트.
> 이 문서는 포트폴리오·이력서·기술 면접에 그대로 인용할 수 있도록 **검증 가능한 사실**과 **설계 의도**를 정리한 것이다.
>
> - 작성자: 김윤호 (rladbsgh27@gmail.com)
> - 기간: 2026-05 ~ (M0~M9 완료)
> - 형태: 1인 풀스택 설계·구현
> - 관련 문서: [ARCHITECTURE.md](./ARCHITECTURE.md) · [PRD.md](./PRD.md) · [ERD.md](./ERD.md) · [API.md](./API.md)

---

## 0. 30초 요약 (Elevator Pitch)

> **"코레일 수준의 트래픽 폭증 상황에서 좌석 중복 예매를 0건으로 막는 고동시성 예매 플랫폼을, 6개 마이크로서비스 + Gateway + Discovery 구조로 설계·구현했습니다.**
> **Redis Lua 비트마스크로 segment(구간) 단위 좌석 락을 원자적으로 처리하고, Kafka 기반 Saga Choreography로 결제-좌석 확정의 분산 트랜잭션 정합성을 보장하며, Prometheus·Grafana·Zipkin 풀스택 관측성으로 6개 서비스에 걸친 단일 예약 흐름을 추적할 수 있게 만든 것이 핵심입니다."**

| 항목 | 수치 |
|------|------|
| 마이크로서비스 | 6 비즈니스 + Gateway + Eureka (총 8 모듈) |
| 백엔드 코드 | Java 213개 파일 / 약 10,800 LOC |
| 공통 이벤트 계약 | Kafka 이벤트 record 12종 (`common-lib`) |
| DB 스키마 | 4개 격리 스키마 (Database per service) |
| Flyway 마이그레이션 | 12개 버전 스크립트 |
| 동시성 핵심 | Redis Lua 스크립트 2종 (reserve / rollback) |
| 관측성 | Prometheus 메트릭 12종 + Zipkin 6-서비스 trace |
| 프론트엔드 | React 19 + Vite + TS (20개 모듈) |
| 인프라 | docker-compose 단일 명령 (15개 컨테이너) |
| 부하 검증 (M9) | JMeter 1,000 동접: **에러 0% / 예약 p95 14ms / overbooking 0** (로컬 풀스택) |

---

## 1. 프로젝트 한 줄 정의

**XRail MSA** — "고동시성 환경에서 기차 좌석을 **segment(구간) 단위**로 안전하게 예매하는 서비스를, **운영·관측 가능한 MSA**로 제공한다."

기존에 따로 운영하던 두 모놀리식 프로젝트(① 풍부한 도메인의 기차 예매 XRail, ② 검증된 고동시성·관측성의 콘서트 예매 ticketing-system)를 **단일 MSA 제품으로 통합**하면서, 도메인 깊이와 운영 성숙도를 한곳에 모은 것이 출발점이다.

---

## 2. 시스템 아키텍처 (MSA 강점)

```
React SPA ──> API Gateway (JWT 단일 검증 + 헤더 주입)
                  │
                  ├── auth-service        (회원/비회원/OAuth2/JWT)
                  ├── train-service       (노선/스케줄/예약/좌석락/Saga 보상 주인)
                  ├── queue-service        (대기열 Sorted Set + SSE)
                  ├── payment-service      (결제/멱등/DLT)
                  └── notification-service (이벤트 → 알림)
                  │
       Eureka(Discovery) · Kafka(이벤트 버스) · Redis(DB0~3) · MySQL(4 스키마)
       Prometheus · Grafana · Zipkin (관측성)
```

### 2.1 왜 이 구조가 강점인가 (면접 포인트)

| 설계 결정 | 무엇을 했나 | 왜 (강점) |
|----------|------------|-----------|
| **Database per service** | 4개 스키마를 별도 DB 사용자/권한으로 격리, 크로스 서비스 FK **전면 금지** | 서비스 간 결합도 제거 → 독립 배포·독립 스케일 가능. 타 서비스 데이터는 `userId(Long)` + 스냅샷 컬럼으로 비정규화 |
| **Gateway 단일 인증** | JWT 검증을 Gateway GlobalFilter에서만 수행하고 `X-User-Id/Role/Name` 헤더로 downstream 전달 | downstream 서비스는 인증 로직 제로 → 네트워크 홉·중복 코드 절감. 인증 정책을 한 곳에서 관리 |
| **Saga Choreography** | 중앙 오케스트레이터 없이 Kafka 이벤트 연쇄로 분산 트랜잭션 처리, 보상 책임은 train-service로 단일화 | 서비스 간 동기 의존 제거 → 한 서비스가 죽어도 전체가 멈추지 않음. 디버깅은 `reservation_saga_log` 테이블로 보완 |
| **common-lib 모노레포** | Kafka 이벤트 record 12종 + 토픽/헤더 상수를 공통 모듈로 추출 | 서비스 간 이벤트 계약 중복 정의 제거, 컴파일 타임 타입 안전성 확보 |
| **장애 격리 설계** | payment 다운 시 예약은 PENDING으로 남았다가 20분 후 자동 보상, Redis/Kafka/MySQL 다운 시나리오별 격리 정책 명문화 | 단일 의존 장애가 전체 장애로 번지지 않음 (ARCHITECTURE.md §12) |

---

## 3. 핵심 기술 성과 (Problem → Solution → Result)

이력서에 가장 강하게 쓸 수 있는 5개 기술 하이라이트.

### 🔑 성과 1 — Redis Lua 비트마스크로 "구간 단위" 좌석 동시성 0 충돌

- **Problem**: 기차 좌석은 콘서트와 달리 **구간(segment)** 개념이 있다. 서울→부산 좌석을 서울→대전 / 대전→부산으로 쪼개 팔 수 있어야 하고, 겹치는 구간만 정확히 충돌로 판정해야 한다. 단순 `lock per seat`으로는 표현 불가.
- **Solution**:
  - 좌석을 **비트열(bitmask)**로 모델링. 키 `sch:{scheduleId}:seat:{seatId}`, 비트 인덱스 = `[startStationIdx, endStationIdx-1]`.
  - 예약 구간의 비트를 검사→설정하는 동작을 **단일 Lua 스크립트**로 묶어 Redis에서 원자적으로 실행 (check-and-set이 네트워크 왕복 없이 atomic).
  - 한 비트라도 이미 set이면 `0`(충돌) 반환 → 즉시 부분 롤백 후 `409 SEAT_ALREADY_TAKEN`.
  - **3중 안전망**: ① Lua atomic 락 → ② DB 더블체크(`existsOverlap`, 비관적 락) → ③ 5분 주기 Reconciliation 스케줄러(Redis↔DB 정합 보정, DB가 source of truth).
- **Result**: 100 스레드 동일 좌석 동시 예약에서 **overbooking 0건**. Redis 장애로 비트마스크가 깨져도 DB 더블체크 + 재정합화가 최후 방어선으로 동작.

```lua
-- reserve_seat.lua (핵심) — 구간 비트를 atomic하게 검사 후 설정
for i = startIdx, endIdx - 1 do
    if redis.call('GETBIT', key, i) == 1 then return 0 end  -- 충돌
end
for i = startIdx, endIdx - 1 do
    redis.call('SETBIT', key, i, 1)                          -- 점유
end
```

### 🔑 성과 2 — Kafka Saga Choreography로 결제-좌석 확정 분산 트랜잭션 정합성

- **Problem**: "결제 성공 → 좌석 확정", "결제 실패 → 좌석 해제"가 서로 다른 서비스(payment / train)에 걸친 분산 트랜잭션. 2PC 없이 **최종 일관성(eventually consistent)**을 보장해야 한다.
- **Solution**:
  - 오케스트레이터 없는 **Choreography** 방식. `payment.completed` / `payment.failed` 이벤트를 train-service가 구독해 보상 처리.
  - **보상 책임을 train-service로 단일화**: 결제 실패/타임아웃/사용자 취소/정합 깨짐 등 5개 시나리오의 보상 액션을 한 서비스가 책임 (보상 매트릭스 ARCHITECTURE.md §6.5).
  - **멱등 컨슈머**: 이미 처리된 `eventId`이거나 이미 `PAID` 상태면 no-op 후 정상 커밋 → 중복 이벤트 안전.
  - **Transactional Outbox** + **DLT(Dead Letter Topic)**: 이벤트 발행 누락 방지 + 처리 실패 메시지 격리.
- **Result**: 결제 happy path / 실패 보상 / 20분 미결제 타임아웃 / 사용자 취소 / 환불 saga 전 경로 동작. 미결제 좌석은 스케줄러가 자동 회수.

### 🔑 성과 3 — 대기열(Virtual Waiting Room) + SSE 실시간 알림 + 평시 우회 하이브리드

- **Problem**: 인기 노선 오픈 순간 트래픽이 폭증하면 DB 커넥션 풀이 마른다. 그렇다고 평상시에도 모두 대기열을 태우면 UX가 나빠진다.
- **Solution**:
  - Redis **Sorted Set** 기반 대기열 + 3초/100명 단위 승급 스케줄러 + HMAC 큐 토큰 발급.
  - **SSE(Server-Sent Events)** 로 순번·예상 대기시간 실시간 push (25초 heartbeat로 프록시 idle timeout 회피), **EventSource 실패 시 polling fallback** 자동 전환 → 동일 클라이언트 훅 하나로 양 경로 지원.
  - **하이브리드 입장 제어**(코레일/NetFunnel 방식): `AUTO`(평시 우회 + 임계치 초과 시 자동 대기) / `FORCE_ON` / `FORCE_OFF` 3모드를 운영자가 실시간 토글. 평시엔 0초 통과, 폭증 시 자동 게이트.
  - Redis Pub/Sub(`RTopic`) 기반 SSE로 **queue-service 수평 확장** 지원 (동일 userId가 다른 인스턴스에 붙어도 일관성 유지).
- **Result**: 1,000명 큐 등록 → 단계적 활성화 정상. 평시 사용자는 대기 없이 즉시 예약 진입.

### 🔑 성과 4 — 풀스택 관측성: 6개 서비스를 가로지르는 단일 trace

- **Problem**: 서비스 6개 + 인프라 4개 환경에서 "이 예약이 어디서 막혔는지"를 즉시 파악할 수 있어야 한다.
- **Solution**:
  - **Prometheus + Grafana**: 표준 메트릭(JVM/HTTP/HikariCP/Kafka) 외 **도메인 커스텀 메트릭 12종**(좌석 락 충돌률, 결제 성공/실패, 대기열 크기, 보상 발동 횟수 등)을 Micrometer로 노출.
  - **Zipkin 분산 트레이싱**: HTTP는 Brave 자동 전파, **Kafka는 `TracingProducerInterceptor`/`TracingConsumerInterceptor`로 trace를 이벤트 헤더에 명시 전파** (spring-kafka 3.x가 자동 전파 안 하는 부분을 직접 해결).
  - **로그**: `traceId`/`spanId`/`userId` MDC 주입, JSON 포맷.
- **Result**: 단일 예약의 trace가 `gateway → train → kafka → payment → kafka → train → kafka → notification` 전체로 연결. DLT 적재 시 알림.

### 🔑 성과 5 — JMeter 1,000 동접 부하 테스트: 측정→진단→수정 5회 반복으로 목표 전 항목 달성

- **Problem**: PRD 목표(1,000 동접에서 p95 < 800ms, error < 1%, overbooking 0)를 실제 부하로 검증해야 한다. 첫 실행 결과는 에러 56% — "기능이 도는 것"과 "부하를 견디는 것"은 완전히 다른 문제였다.
- **Solution** (run1~5, 각 라운드마다 로그·메트릭·타임라인 분석으로 병목 1개씩 제거):
  1. **bcrypt strength 12 → 인증 붕괴** (run1, 에러 56%): 1,000명 가입+로그인 = 해싱 2,000회 ≈ 540 코어-초 → Gateway TimeLimiter 5s 초과 → Circuit Breaker 오픈 → 503 연쇄. 강도를 프로퍼티화(`auth.bcrypt.strength`, OWASP 하한 10 코드로 강제)하고, 계정 가입은 측정 시나리오에서 분리(사전 시드)해 PRD 정의(search→reserve→pay)와 정렬.
  2. **좌석 가용 조회가 비트당 Redis 왕복** (run2, 에러 18%): `isFree()`가 GETBIT를 좌석×구간마다 호출 — 검색 1회당 최대 ~2,000 왕복, 그동안 `@Transactional`이 DB 커넥션 점유 → HikariCP(30) 고갈 → 30s 대기 → 503. **배치 Lua 스크립트(`check_free_batch.lua`)로 스케줄당 1왕복**으로 축소 → 검색 p95 **4초 → 21ms**.
  3. **소비된 큐 토큰 재반환** (run3·4, 매 런 동일 시간대에 401 결정적 재현): `QueueService.enter()`가 기존 active 버킷의 **이미 소비된** HMAC 토큰을 재반환 → 재예매 401. 실패 타임라인(램프업 오프셋과 1:1 대응)을 역추적해 이전 런의 실패 사용자 버킷(TTL 600s)이 원인임을 규명 — enter 시 신규 토큰 재발급으로 근본 수정. (프론트엔드 우회만 존재하던 잠복 버그를 부하 테스트가 서버측에서 적발)
- **Result**: 최종 run5 — **7,000 샘플 에러 0%, 예약 p95 14ms, 조회 p50 11ms** (1,000명 전원 예매~결제 완주). 동일 좌석 100스레드 SyncTimer 동시 발사 → **201 성공 정확히 1건 + 409 충돌 99건, DB 활성 티켓 1건** (overbooking 0). JMeter 자산(메인 JMX, 동일좌석 JMX, 계정 시드 스크립트)을 `docs/jmeter/`에 재현 가능한 형태로 정리.

---

## 4. 트러블슈팅 — 직접 잡은 동시성/분산 버그 (면접 단골 질문 대비)

> 동작하는 코드를 넘어 **"왜 깨지는지"를 이해하고 고친** 사례. 분산 시스템 디버깅 역량을 보여주는 핵심 자료.

| # | 버그 | 근본 원인 | 해결 |
|---|------|----------|------|
| F-07 | 중복 `payment.failed`가 **타 예약 좌석 비트를 해제** | 멱등/순서 가드 부재로 rollback이 재실행됨 | `status != PENDING` 가드 추가 (멱등 컨슈머) |
| F-14 | PENDING 예약이 **매 60초 무한 rollback** | detached 엔티티는 dirty-check flush가 안 돼 status가 PENDING으로 남음 | 트랜잭션 내 `findById` 재조회 + PAID race 가드 |
| F-15 | 알림 UNIQUE 위반이 **트랜잭션 전체를 rollback-only**로 오염 | 단일 `@Transactional`에서 catch해도 `UnexpectedRollbackException` | 채널별 `REQUIRES_NEW` 격리(`NotificationLogWriter`) |
| F-16 | Reconciliation이 **in-flight 예약 비트를 오삭제** | 순간 스냅샷만으로 phantom 판정 | **연속 2회 확인** 시에만 제거(`previousPhantomCandidates`) |
| F-09 | Rate limit이 순간적으로 **전면 해제** | fallback `localCounts` 전체 `clear()` | 지난 window 키만 정리 |
| 결제 락 | Redisson lock이 트랜잭션 경계 안에 위치 | `@Transactional` 커밋 전에 lock 해제 위험 | `TransactionTemplate`으로 교체, lock을 트랜잭션 **바깥**으로 |
| Kafka 무한재시도 | DLT 미연결로 예외가 무한 재시도 루프 | recoverer 부재 | `DeadLetterPublishingRecoverer` + `FixedBackOff(1s, 2회)` |
| 부하: CB 연쇄 503 | bcrypt(12) 해싱이 CPU 포화 → 5s 타임아웃 → **CB 오픈이 정상 요청까지 차단** | 인증 같은 CPU-bound 작업이 동기 경로에서 폭증 | bcrypt 강도 설정화 + 가입을 측정 경로에서 분리. "CB는 증상, 원인은 upstream 지연"이라는 진단 순서 학습 |
| 부하: 커넥션 풀 고갈 | 좌석 조회가 비트당 GETBIT(검색 1회 ~2,000왕복)로 **트랜잭션 내 DB 커넥션을 장시간 점유** | Redis I/O가 tx 안에 섞여 풀 점유 시간 증폭 | 배치 Lua 1왕복으로 축소 (검색 p95 4s→21ms). HikariCP 로그(`active=30, waiting=37`)로 역추적 |
| 부하: 소비 토큰 재반환 | 재진입 시 active 버킷의 **이미 소비된 큐 토큰**을 재반환 → 예약 401 | enter()가 버킷 존재 시 기존 토큰 그대로 반환 | enter 시 신규 토큰 재발급. 매 런 동일 램프업 오프셋에서 재현되는 실패 타임라인으로 규명 |

**공통 교훈으로 정리 가능한 것**: ① 분산 환경의 멱등성, ② JPA 영속성 컨텍스트와 dirty checking, ③ 트랜잭션 전파(`REQUIRES_NEW`)와 rollback-only 마킹, ④ 락과 트랜잭션의 경계 순서.

---

## 5. 보안 설계 강점

| 위협 | 대응 |
|------|------|
| Header 스푸핑 (`X-User-Id` 위조) | Gateway가 inbound `X-User-*` 헤더를 **무조건 제거 후 재주입** |
| 큐 토큰 Replay | HMAC-SHA256 서명 + 짧은 TTL(10분) + 1회용 Idempotency-Key |
| 결제 중복 제출 | Idempotency-Key + Redisson 버킷(PROCESSING/COMPLETED) + JPA `@Version` 낙관 락 |
| 무차별 가입/큐 등록 | Bucket4j(Redis + Lua) per-IP 레이트리미트 + CAPTCHA |
| JWT 탈취 | Refresh 토큰 **회전 체인** + Redis 미러로 강제 무효화, RDB엔 SHA-256 해시 저장 |
| 비밀번호 | bcrypt (Spring Security `PasswordEncoder`), 평문 저장 전면 금지 |
| SQL Injection | Spring Data + QueryDSL 파라미터 바인딩 강제 |

---

## 6. 기술 스택

| 영역 | 스택 |
|------|------|
| 언어/런타임 | Java 21 |
| 프레임워크 | Spring Boot 3.4.x, Spring Cloud 2024.0.0 |
| MSA 인프라 | Spring Cloud Gateway(WebFlux/Netty), Eureka |
| 데이터 | MySQL 8 (스키마 격리), Spring Data JPA + Hibernate + QueryDSL 5.1, Flyway |
| 캐시/락/큐 | Redis + Redisson 3.41 (logical DB 0~3) |
| 메시징 | Apache Kafka(Confluent 7.5), spring-kafka 3.x, Transactional Outbox, DLT |
| 인증 | Spring Security, JJWT 0.12, OAuth2(Kakao/Naver) |
| 복원력/제어 | Resilience4j(Circuit Breaker), Bucket4j(Rate Limit) |
| 관측성 | Micrometer, Prometheus, Grafana, Brave + Zipkin |
| 프론트 | React 19, Vite 7, TypeScript, react-router 7, Axios, EventSource(SSE) |
| 인프라/부하 | Docker Compose(15 컨테이너), JMeter |

---

## 7. 이력서용 불릿 (복사·붙여넣기)

### 국문 (1~2줄 압축형)

- **고동시성 기차 예매 MSA 설계·구현 (1인)** — Spring Cloud Gateway + Eureka 기반 6개 마이크로서비스로 분리, Database per service·크로스 FK 제거로 독립 배포 가능한 구조 설계.
- **Redis Lua 비트마스크 기반 구간(segment) 단위 좌석 동시성 제어** — check-and-set을 단일 Lua 스크립트로 원자화, DB 더블체크 + 재정합화 스케줄러 3중 안전망으로 **100 스레드 동시 예약 overbooking 0건** 달성.
- **Kafka Saga Choreography로 결제-좌석 확정 분산 트랜잭션 정합성 확보** — 멱등 컨슈머 + Transactional Outbox + DLT로 최종 일관성 보장, 보상 책임을 단일 서비스로 집중해 5개 실패 시나리오 자동 복구.
- **대기열(Virtual Waiting Room) + SSE 실시간 알림** — Redis Sorted Set + 스케줄러 승급, SSE/polling 자동 fallback, 평시 우회·임계치 자동 활성화 하이브리드 입장 제어 구현.
- **풀스택 관측성** — Prometheus 도메인 메트릭 12종 + Zipkin Kafka 헤더 전파로 6개 서비스를 가로지르는 단일 예약 trace 구현.
- **분산 동시성 버그 디버깅** — 멱등성 누락으로 인한 타 예약 좌석 해제, JPA detached 엔티티 무한 rollback, 트랜잭션 rollback-only 오염 등 다수 동시성 결함을 근본 원인 분석 후 수정.
- **JMeter 1,000 동접 부하 테스트 — 측정→진단→수정 반복으로 에러 56% → 0% 달성** — bcrypt CPU 포화로 인한 Circuit Breaker 연쇄 503, Redis 왕복 폭증(검색당 ~2,000회)으로 인한 HikariCP 고갈, 소비된 큐 토큰 재발급 버그를 로그·타임라인 분석으로 규명·수정해 예약 p95 14ms·overbooking 0건 검증.

### English

- Designed and built a high-concurrency train-booking platform as a **microservices architecture** (6 business services + API Gateway + Eureka) with strict **database-per-service** isolation and no cross-service foreign keys.
- Implemented **segment-level seat concurrency control using a Redis Lua bitmask**, making check-and-set atomic in a single script; combined with a DB double-check and a reconciliation scheduler (triple safety net) to achieve **zero overbooking under 100-thread contention**.
- Guaranteed distributed-transaction consistency for payment/seat confirmation via **Kafka Saga choreography** with idempotent consumers, the transactional outbox pattern, and dead-letter topics; centralized compensation logic to recover from 5 failure scenarios.
- Built a **virtual waiting room** (Redis sorted set + promotion scheduler) with **real-time SSE notifications and automatic polling fallback**, plus a hybrid admission controller (bypass under normal load, auto-gate above threshold).
- Delivered **full-stack observability**: 12 custom Prometheus domain metrics and end-to-end Zipkin tracing across all 6 services via manual Kafka header propagation.
- Drove a **1,000-concurrent-user JMeter load test from 56% errors to 0%** through five measure-diagnose-fix iterations: resolved a bcrypt-induced circuit-breaker cascade, collapsed ~2,000 Redis round trips per search into a single batched Lua call (search p95 4s → 21ms), and root-caused a consumed-queue-token reissue bug via failure-timeline analysis — final results: reservation p95 14ms, 0 overbooking under 100-thread same-seat contention.

---

## 8. 면접 예상 질문 & 답변 포인트

| 질문 | 답변 핵심 |
|------|-----------|
| 왜 모놀리스가 아니라 MSA인가? | 도메인별 독립 스케일링(좌석 락은 train, 큐는 queue가 병목 지점이 다름) + 장애 격리. 단, 분산의 복잡성(디버깅, 일관성)은 trade-off로 인정하고 saga log·관측성으로 보완. |
| Saga에서 왜 Orchestration이 아니라 Choreography? | 서비스 간 의존도를 낮추기 위해. 대신 디버깅 난이도가 올라가므로 `reservation_saga_log`에 in/out 이벤트를 모두 기록하고 Zipkin으로 보완. |
| 좌석 락에 왜 Redisson RLock이 아니라 Lua 비트마스크? | 좌석이 "구간" 단위라 단순 lock-per-seat으로 표현 불가. 비트마스크가 segment 겹침을 정밀하게 판정. |
| Redis가 죽으면? | 좌석 락 불가로 503(장애 격리) + DB 더블체크가 정합성 최후 방어선 + Reconciliation이 사후 보정. |
| 중복 이벤트가 오면? | `eventId` 기준 멱등 처리 + 상태 가드(`status != PENDING`, 이미 `PAID`면 no-op). 실제로 이 가드 부재로 생긴 버그(F-07)를 직접 수정. |
| 동시성 정합을 어떻게 검증했나? | 100 스레드 동일 좌석 SyncTimer 동시 발사 → 201 성공 1건 + 409 충돌 99건, DB 활성 티켓 1건. JMeter 1,000 동접(login→search→queue→reserve→pay) 7,000샘플 에러 0%, 예약 p95 14ms. |
| 부하 테스트에서 뭘 배웠나? | 첫 실행은 에러 56% — ① CB 503은 증상이고 원인은 bcrypt CPU 포화(진단 순서: fallback→TimeLimiter→upstream), ② 트랜잭션 안의 Redis 왕복이 DB 커넥션 점유 시간을 증폭(HikariCP `active=30, waiting=37` 로그로 역추적), ③ 결정적으로 재현되는 실패 타임라인(램프업 오프셋 고정)이 상태 잔존 버그의 지문이라는 것. |

---

## 9. 한계와 다음 단계 (솔직하게 — 면접 신뢰도 ↑)

의도적으로 1차 범위에서 제외한 항목(과한 엔지니어링 회피):

- **HA 미적용** — Gateway/Eureka 이중화, MySQL replica, Kafka 멀티 브로커는 deferred. (단일 인스턴스 가정)
- **실 PG 미연동** — mock PG(`payment.mock.always-fail` 토글)로 성공/실패 경로 검증.
- **Kafka 직렬화 JSON** — Schema Registry/Avro는 호환성 깨질 때 도입 검토.
- **로그 집계** — 1차는 docker compose logs, Loki/ELK는 deferred.
- **queue scope 단일 `global`** — `schedule:{id}`로의 트래픽 분산은 확장 포인트로 남김.

이 항목들을 "안 한 것"이 아니라 **"1인 프로젝트에서 우선순위를 명시적으로 결정한 것"**으로 설명 가능 (PRD.md §7 MoSCoW + §14 Decision Log에 근거 기록).

---

**End of PORTFOLIO.md**
