# queue-service — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 queue-service 작업 시 추가로 지킬 규칙이다.

## 역할

대기열 관리(Redis Sorted Set), 3초/100명 승급 스케줄러, SSE 알림, CAPTCHA stub, 큐 토큰(HMAC) 발급.

## 규칙

### Q1. Redis-only (DB 없음)
- 이 서비스는 RDB가 없다. JPA, Flyway 추가 금지.
- 모든 상태는 Redis에만 저장. 인스턴스 재시작 시 in-memory SSE emitter가 사라지는 것은 정상 동작.
- 키 패턴은 ERD.md §5.3을 엄격히 준수. 임의 키 추가 시 TTL 정책도 함께 명시.

### Q2. 대기열 스케줄러
- 스케줄러 주기: `fixedDelay=3000ms`. `fixedRate` 사용 금지 — 이전 실행이 끝나기 전에 다음이 시작되면 중복 승급 발생.
- 한 번에 승급 인원: `queue.scheduler.batch-size` (기본 100명) 설정값 참조. 하드코딩 금지.
- `queue:scopes` Set을 순회하여 모든 scope 처리. scope 순서에 의존하는 로직 금지.
- 승급 시 `queue:active:{scope}:{userId}` SET + `queue:waiting:{scope}` ZREM은 Lua 스크립트로 atomic 처리권장.

### Q3. SSE Emitter 관리
- `SseEmitter` 생성 시 timeout을 명시적으로 설정 (`10분 = 600_000L`). timeout=0(무한) 사용 금지.
- emitter 완료/타임아웃/에러 콜백에서 반드시 `ConcurrentMap`에서 제거. 메모리 누수 방지.
- 동일 `userId`가 새 SSE 연결을 열면 기존 emitter를 `complete()` 처리 후 교체.
- SSE 이벤트 전파는 Redis RTopic pub/sub(`queue:sse:notify`, `RedisQueueEventListener`)로 인스턴스 간 전달한다. 구독자가 없으면 로컬 emitter로 폴백 — 수평 확장 시에도 동작.

### Q4. SSE heartbeat
- 25초마다 heartbeat 이벤트 전송. 이 값 변경 시 ARCHITECTURE.md §5와 동기화.
- heartbeat는 `event: heartbeat\ndata: {}\n\n` 형식. 빈 주석 라인(`:\n\n`)도 허용되지만 이벤트 형식 통일.

### Q5. 큐 토큰 HMAC
- 토큰 형식: `HMAC_SHA256(userId:scope:exp, QUEUE_HMAC_SECRET)`.
- `QUEUE_HMAC_SECRET`은 환경변수에서만 읽는다. 기본값 하드코딩 금지.
- 발급된 토큰의 TTL = `queue.active-ttl-seconds` (기본 600초). 만료된 토큰은 Redis에서 자동 삭제.
- train-service의 `QueueTokenInterceptor`와 검증 로직이 동일해야 한다. 어느 한쪽만 변경 금지.

### Q6. Idempotency
- `POST /api/queue/token` 중복 요청: `queue:idem:{idemKey}` SETNX 5분. 이미 있으면 기존 rank 응답 반환.
- 동일 userId가 대기열에 이미 있으면 ZADD로 score만 갱신(순서 유지)하지 않고 기존 rank 그대로 반환.

### Q7. CAPTCHA stub
- 1차에서는 모든 CAPTCHA 코드를 통과로 처리. stub 검증 로직을 "항상 성공"이 아닌 설정 토글로 구현.
- `application.yml`에 `captcha.mode: stub` 명시. `real` 모드 미설정 시 그대로 stub 동작 (부팅 실패 요구사항은 PRD R4 참조).
