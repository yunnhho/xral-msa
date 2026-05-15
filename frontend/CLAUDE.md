# frontend — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 frontend 작업 시 추가로 지킬 규칙이다.

## 역할

React 19 SPA. Gateway를 통해 API 호출, SSE 대기열 구독, OAuth2 콜백 처리.

## 규칙

### F1. API 호출
- 모든 API 호출은 `axios` 인스턴스 단일 진입점(`src/api/client.ts`)을 통해서만.
- `Authorization: Bearer <token>` 헤더는 axios interceptor에서 자동 주입. 컴포넌트마다 직접 설정 금지.
- 401 응답 수신 시 interceptor에서 refresh 토큰으로 재발급 시도 → 재발급도 실패하면 로그아웃 처리.
- API base URL은 환경변수(`VITE_API_BASE`) 또는 Vite proxy 사용. 컴포넌트에 `http://localhost:8080` 하드코딩 금지.

### F2. 인증 상태 관리
- access token은 메모리(React Context 또는 Zustand) 저장 우선. localStorage는 refresh token 저장용.
- OAuth2 콜백(`/oauth/callback`)에서 쿼리파라미터 `accessToken`, `refreshToken` 파싱 후 저장, `/home`으로 이동.
- 로그아웃 시 localStorage, 메모리 상태, axios 기본 헤더를 모두 초기화.

### F3. SSE 대기열 (`useQueueStatus` hook)
- `EventSource`는 쿠키 기반 인증 사용 (`withCredentials: true`). 커스텀 헤더로 JWT를 전달하지 않는다.
- SSE 에러 2회 연속 발생 시 polling fallback으로 자동 전환. 이 동작을 prop으로 비활성화하는 옵션 추가 금지.
- `event: active` 수신 즉시 `EventSource.close()` 호출. 토큰 저장 후 좌석 선택 페이지로 이동.
- polling fallback 응답 형식은 SSE `event: rank`/`event: active`의 data와 동일한 JSON 구조 유지.

### F4. 타입 안전성
- API 응답 타입은 `ApiResponse<T>` 제네릭으로 정의(`src/types/api.ts`).
- `any` 타입 사용 금지. 불가피한 경우 `unknown`으로 선언 후 타입 가드.
- Kafka 이벤트 payload 형식은 `common-lib`의 record 구조와 동일하게 TypeScript interface로 미러링.

### F5. 에러 처리
- API 에러는 `ApiResponse.code` 기준으로 분기. HTTP 상태코드만으로 분기하지 않는다.
- `SEAT_ALREADY_TAKEN` → 좌석 선택 화면 갱신 + 토스트 메시지.
- `RATE_LIMITED` (429) → `Retry-After` 헤더 값만큼 대기 후 재시도 안내.
- 전역 에러 바운더리에서 잡지 못한 예외 처리. 흰 화면(WSOD) 방지.

### F6. 컴포넌트 규칙
- 페이지 컴포넌트는 `src/pages/`, 재사용 컴포넌트는 `src/components/`.
- 커스텀 훅은 `src/hooks/`. API 호출이 있는 훅은 `useQuery*` / `useMutation*` 네이밍.
- 하나의 컴포넌트가 두 가지 이상의 비즈니스 책임을 가지면 분리.
- `useEffect` 내 async 함수는 cleanup에서 `AbortController`로 취소. fetch 중 컴포넌트 unmount 시 상태 업데이트 방지.
