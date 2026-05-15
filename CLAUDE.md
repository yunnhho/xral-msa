# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# XRail MSA — 프로젝트 공통 규칙 (대규칙)

> 아래 규칙은 모든 하위 서비스 CLAUDE.md(소규칙)의 베이스다.
> 하위 CLAUDE.md에서 충돌이 없으면 이 규칙이 우선 적용된다.

## P1. 아키텍처 원칙

- **6개 비즈니스 서비스 + 1 Gateway + 1 Discovery** 구조. 새 서비스 추가는 명시적 요청이 있을 때만.
- **Database per service**: 크로스 서비스 FK 절대 금지. 타 서비스 데이터는 `userId(Long)` + 스냅샷 컬럼으로 비정규화.
- **Saga Choreography**: 오케스트레이터 없음. 보상 책임은 `train-service`. 이벤트 흐름 변경 시 `reservation_saga_log` 기록을 먼저 확인.
- **Gateway 단일 인증**: downstream 서비스는 `X-User-Id / X-User-Role / X-User-Name` 헤더를 신뢰. 자체 JWT 검증 로직 추가 금지.

## P2. 패키지 & 네이밍

- 베이스 패키지: `com.xrail.<service>` (예: `com.xrail.train`)
- 계층: `controller → service → repository` (단방향). service가 다른 service를 직접 주입하지 않는다.
- 공통 상수는 `common-lib`의 `Topics`, `Headers`를 사용. 서비스 내부에서 문자열 리터럴로 토픽명/헤더명 중복 정의 금지.

## P3. 공통 코딩 규칙

- **응답**: 모든 REST 응답은 `ApiResponse<T>` envelope 사용.
- **예외**: 비즈니스 오류는 `BusinessException(ErrorCode)` throw. `ErrorCode`에 없는 케이스는 추가 후 사용.
- **엔티티**: JPA 엔티티는 `BaseTimeEntity` 상속. `createdAt` / `updatedAt` 수동 설정 금지.
- **Lombok**: `@Data` 사용 금지 (`@Getter` + 필요한 것만). 엔티티에 `@Setter` 금지.
- **이벤트 DTO**: Kafka 이벤트는 `common-lib`의 Java record 사용. 서비스 내부에 별도 이벤트 클래스 생성 금지.

## P4. Kafka 규칙

- 토픽명은 `Topics.*` 상수만 사용.
- 모든 Kafka 이벤트는 `eventId(UUID)`, `occurredAt(ISO-8601)`, `traceId` 필드를 포함해야 한다.
- 컨슈머는 반드시 **멱등 처리**: 이미 처리된 `eventId`이면 no-op 후 정상 커밋.
- DLT 처리 없이 예외를 그냥 throw하면 무한 재시도 루프 발생 — 반드시 `@DltHandler` 또는 `DeadLetterPublishingRecoverer` 연결.

## P5. Redis 규칙

- 서비스별 logical DB 인덱스를 반드시 지킨다: auth=0, train=1, queue=2, payment=3.
- 키 패턴은 ERD.md §5에 정의된 패턴을 그대로 사용. 임의 키 패턴 생성 금지.
- TTL 없는 키는 메모리 누수 위험 — 모든 신규 키에 TTL 설정 여부를 명시적으로 결정할 것.

## P6. 보안 규칙

- 비밀번호는 반드시 bcrypt (`PasswordEncoder`). 평문 저장 절대 금지.
- JWT secret, HMAC secret은 환경변수에서만 읽는다 (`${JWT_SECRET}`, `${QUEUE_HMAC_SECRET}`). 하드코딩 금지.
- Gateway inbound `X-User-*` 헤더는 무조건 제거 후 재주입. downstream에서 이 헤더를 직접 받을 경우 검증 로직 추가 금지 (Gateway가 보장).

## P7. 관측성 규칙

- 모든 서비스는 `/actuator/prometheus` 노출 필수.
- 도메인 이벤트(예약 생성, 결제 성공 등)는 Micrometer Counter/Gauge로 기록. 메트릭명은 `xrail.<service>.<event>` 패턴.
- 로그에 `traceId`, `spanId`, `userId` MDC 포함. `log.info("처리 완료")` 같은 컨텍스트 없는 로그 금지.

## P8. 테스트 규칙

- 핵심 컴포넌트(Service, Scheduler, Lua 스크립트)는 단위 테스트 필수.
- 컨트롤러 테스트는 `@WebMvcTest` + MockBean 사용.
- Kafka 통합 테스트는 `@EmbeddedKafka` 사용.
- `@SpringBootTest` 남용 금지 — 슬라이스 테스트를 먼저 고려.
