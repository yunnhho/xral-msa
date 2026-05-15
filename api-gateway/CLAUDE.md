# api-gateway — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 api-gateway 작업 시 추가로 지킬 규칙이다.

## 역할

모든 외부 트래픽의 단일 진입점. JWT 검증, 헤더 주입, CORS, 레이트리미트, 라우팅.

## 규칙

### G1. 비즈니스 로직 금지
- Gateway는 **횡단 관심사(cross-cutting concerns)** 만 처리. 예약 로직, 결제 로직이 들어오면 해당 서비스로 이동시켜라.
- downstream 서비스 응답을 Gateway에서 가공(transform)하지 않는다. 그대로 전달.

### G2. JWT 검증 (핵심)
- JWT 검증은 `GlobalFilter`에서 **한 곳에서만** 수행.
- inbound `X-User-Id`, `X-User-Role`, `X-User-Name` 헤더는 **unconditional 제거** 후 JWT 파싱 값으로 재주입. 제거 순서가 주입보다 반드시 먼저여야 한다.
- 인증 면제 경로 (`/api/auth/**`, `/oauth2/**`, `/login/oauth2/**`, actuator 등) 변경 시 보안 검토 필수. 면제 경로를 넓히는 방향으로 수정할 때는 반드시 사유를 코멘트로 남긴다.

### G3. 헤더 스푸핑 방지
- 클라이언트가 `X-User-*` 헤더를 직접 전송해도 Gateway가 덮어쓰므로 downstream은 안전하다.
- 이 보장이 깨지는 코드(예: 조건부 제거, 화이트리스트 IP 예외) 절대 추가 금지.

### G4. SSE 라우팅
- `/api/queue/subscribe` 경로의 라우트에는 반드시 `response-timeout: 0` 메타데이터 설정.
- SSE 라우트에 `RemoveResponseHeader=Content-Length` 필터 적용 (chunked 전송 보장).
- SSE 라우트에 일반 retry 필터 적용 금지 — 연결 끊기면 클라이언트가 재연결한다.

### G5. Bucket4j 레이트리미트
- 레이트리미트 설정은 `application.yml`의 엔드포인트별 버킷 설정으로 관리. 코드 내 하드코딩 금지.
- 429 응답에는 반드시 `Retry-After` 헤더 포함.
- 레이트리미트 한도 변경 시 API.md §9의 표도 함께 업데이트.

### G6. Resilience4j 서킷브레이커
- 서킷브레이커 fallback은 의미 있는 정적 응답을 반환. `null`이나 빈 응답 반환 금지.
- `/actuator/circuitbreakers` 엔드포인트는 반드시 노출 유지.

### G7. WebFlux 주의사항
- Gateway는 reactive(Netty) 기반. blocking I/O 절대 사용 금지 (`JDBC`, `RestTemplate` 등).
- `Mono`/`Flux` 체인에서 예외 처리는 `.onErrorResume()` 또는 `@ExceptionHandler`로. `try-catch` 블록으로 감싸지 않는다.
