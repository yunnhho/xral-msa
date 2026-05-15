# auth-service — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 auth-service 작업 시 추가로 지킬 규칙이다.

## 역할

회원/비회원 가입·로그인, JWT 발급, OAuth2(Kakao/Naver), refresh 토큰 회전.

## 규칙

### A1. 비밀번호 처리
- 비밀번호 저장은 `PasswordEncoder.encode()` 만 사용. 어디서도 평문 비교 금지.
- bcrypt rounds는 10 이상. 테스트 편의를 위해 낮추지 않는다 (`NoOpPasswordEncoder` 등 사용 금지).
- 비회원 4~6자리 숫자 비밀번호도 동일하게 bcrypt 처리.

### A2. JWT 규칙
- `JWT_SECRET` 환경변수에서만 읽는다. 기본값으로 짧거나 예측 가능한 문자열 사용 금지 (`application.yml`의 `${JWT_SECRET:default}` 형태 금지).
- access token TTL = `JWT_ACCESS_TTL_MS` (기본 30분). 임의로 늘리지 않는다.
- JWT payload에 비밀번호 해시, 개인정보 민감 필드 포함 금지. `userId`, `role`, `name`만.

### A3. Refresh 토큰 회전
- refresh 토큰 재발급 시 반드시 이전 토큰 `revoked_at` 갱신 + Redis `rt:{userId}` 갱신.
- DB에는 SHA-256 해시만 저장. 평문 저장 절대 금지.
- Redis 미러(`rt:{userId}`) 검증 → miss 시 DB fallback → DB에도 없으면 `UNAUTHORIZED`.
- 이미 revoked된 refresh 토큰으로 재발급 시도 시 해당 사용자의 모든 토큰 즉시 폐기 (탈취 감지).

### A4. OAuth2 처리
- `CustomOAuth2UserService`에서 소셜 계정을 DB upsert 후 **자체 JWT 발급**. 소셜 provider의 access_token을 클라이언트에 노출하지 않는다.
- OAuth2 성공 후 redirect URI는 `http://localhost:5173/oauth/callback`으로 고정. 외부 URI로 redirect 금지.
- `social_provider + social_id` 조합의 unique 보장. 같은 소셜 계정이 두 번 가입되지 않도록.

### A5. 비회원 (NonMember)
- `accessCode`는 nanoid 10자. UUID 사용 금지 (너무 김).
- 비회원 인증 응답에 `role: ROLE_NON_MEMBER` 명시. MEMBER 권한 부여 금지.
- 비회원 로그인은 `accessCode + phone + password` 세 가지 모두 일치해야 성공.

### A6. 스키마 격리
- `xrail_auth` 스키마만 접근. train/payment/notify 테이블 직접 조회 금지.
- 타 서비스에서 사용자 정보 필요 시 Kafka 이벤트(`user.signed-up`) 또는 헤더 스냅샷으로.
