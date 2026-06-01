# XRail MSA — 버그 수정 세션 가이드

> 모든 마일스톤(M0~M9) 완료 후 실제 웹 동작 기반 버그 발견 및 수정 세션 운영 가이드.
> 각 세션은 이 문서만 읽고 독립적으로 작업할 수 있다.

---

## 공통 사전 정보

### 서비스 포트 맵

| 서비스 | 포트 | Swagger UI |
|---|---|---|
| API Gateway | 8080 | — |
| auth-service | 8081 | http://localhost:8081/swagger-ui/index.html |
| train-service | 8082 | http://localhost:8082/swagger-ui/index.html |
| queue-service | 8084 | http://localhost:8084/swagger-ui/index.html |
| payment-service | 8085 | http://localhost:8085/swagger-ui/index.html |
| notification-service | 8086 | http://localhost:8086/swagger-ui/index.html |
| Eureka Dashboard | 8761 | http://localhost:8761 |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3000 | http://localhost:3000 (admin/admin) |
| Zipkin | 9411 | http://localhost:9411 |
| **Frontend (Vite)** | **5173** | **http://localhost:5173** |

### 전체 페이지 & 라우팅 구조

```
/login              LoginPage          — 공개
/signup             SignupPage         — 공개
/guest/login        GuestLoginPage     — 공개
/oauth/callback     OAuthCallbackPage  — 공개
/home               HomePage           — 인증 필요
/queue              QueuePage          — 인증 필요
/seats              SeatPage           — 인증 필요
/payment            PaymentPage        — 인증 필요
/reservations       ReservationsPage   — 인증 필요
/*                  → /home 리다이렉트
```

인증 없이 `/home` 이하 경로 접근 시 → `/login` 리다이렉트 (`RequireAuth` 컴포넌트).

### 기동 순서

```bash
# 인프라
docker-compose up -d mysql redis zookeeper kafka prometheus grafana zipkin

# 서비스 (각 별도 터미널)
./gradlew :discovery-server:bootRun
./gradlew :auth-service:bootRun
./gradlew :api-gateway:bootRun
./gradlew :train-service:bootRun
./gradlew :queue-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :notification-service:bootRun

# 프론트엔드
cd frontend && npm run dev
```

### 버그 보고 형식

```
[BUG] <한 줄 요약>
- 세션: S{N}
- 페이지/경로: <URL 또는 컴포넌트명>
- 재현 단계: 1. ... 2. ... 3. ...
- 기대 동작: <무엇이 되어야 하는가>
- 실제 동작: <무엇이 일어났는가>
- 관련 파일: <파일:라인 (알면)>
- 심각도: CRITICAL / HIGH / MEDIUM / LOW
```

---

## Session 1 — 로그인 / 회원가입 / 인증 플로우

**담당 페이지:** `LoginPage`, `SignupPage`, `OAuthCallbackPage`, `AuthContext`  
**URL 진입점:** http://localhost:5173/login  
**사용 MCP:** `chrome-devtools` (헤더·쿠키 검사), `playwright` (자동화 시나리오)

---

### 1-A. 로그인 페이지 (`/login`)

#### 화면 구성 전체 목록
| 요소 | 타입 | 동작 |
|---|---|---|
| 이메일 입력 | input[type=email] | 입력값 바인딩 |
| 비밀번호 입력 | input[type=password] | 입력값 바인딩 |
| 로그인 버튼 | button[type=submit] | POST /api/auth/login |
| 카카오 로그인 | `<a href="/oauth2/authorization/kakao">` | 카카오 OAuth2 리다이렉트 |
| 네이버 로그인 | `<a href="/oauth2/authorization/naver">` | 네이버 OAuth2 리다이렉트 |
| 회원가입 링크 | `<Link to="/signup">` | /signup 이동 |
| 비회원 로그인 링크 | `<Link to="/guest/login">` | /guest/login 이동 |
| 에러 메시지 | `<p>` | 로그인 실패 시 표시 |
| 가입 완료 메시지 | `<p style=success>` | /signup → /login 이동 시 state.signupSuccess=true이면 표시 |
| 로그인 중... | button disabled | 요청 중 표시 |

#### 테스트 시나리오

**T1-1. 정상 로그인**
1. http://localhost:5173/login 접속
2. 이메일: `test@test.com`, 비밀번호: `Test1234!` 입력
3. 로그인 버튼 클릭
4. 확인: `/home` 으로 이동
5. 확인: localStorage에 `refreshToken` 저장됨 (DevTools → Application → localStorage)
6. 확인: 헤더 Nav에 사용자 이름 표시

**T1-2. 잘못된 비밀번호**
1. 이메일: `test@test.com`, 비밀번호: `WrongPass!` 입력 → 로그인 클릭
2. 기대: 에러 메시지 표시, 페이지 유지
3. 확인: 에러 문자열이 API 응답 `message` 필드 값과 일치

**T1-3. 존재하지 않는 계정**
1. 이메일: `nobody@nowhere.com`, 비밀번호: `Test1234!` → 로그인 클릭
2. 기대: 에러 메시지 표시

**T1-4. 이메일 형식 오류**
1. 이메일: `notanemail` 입력 → 로그인 클릭
2. 기대: 브라우저 기본 HTML5 validation (`type=email`) 로 제출 차단

**T1-5. 빈 폼 제출**
1. 아무것도 입력 안 하고 로그인 클릭
2. 기대: required 속성에 의해 브라우저 validation 차단

**T1-6. 로딩 상태 중 버튼 disabled**
1. 네트워크를 Slow 3G로 throttle (DevTools → Network → Throttling)
2. 로그인 클릭
3. 기대: 버튼 텍스트 "로그인 중..." + disabled
4. 기대: 완료 후 정상 상태 복귀

**T1-7. 회원가입 직후 성공 메시지 표시**
1. `/signup`에서 가입 성공 → `/login`으로 이동됨
2. 기대: 초록색 "회원가입이 완료되었습니다. 로그인해주세요." 배너 표시
3. 페이지 새로고침 후 기대: 배너 사라짐 (navigation state 초기화)

**T1-8. 이미 로그인된 상태에서 /login 접근**
1. 로그인 완료 상태에서 주소창에 `/login` 직접 입력
2. 기대: (현재 코드에는 가드 없음 — 로그인 페이지 그대로 표시될 수 있음. 버그 여부 확인)

**T1-9. 카카오 로그인 링크 동작**
1. "카카오 로그인" 클릭
2. 기대: Gateway `/oauth2/authorization/kakao`로 이동 (302 리다이렉트 발생)
3. 확인: DevTools Network 탭에서 리다이렉트 체인 확인

**T1-10. 네이버 로그인 링크 동작**
1. "네이버 로그인" 클릭
2. 기대: Gateway `/oauth2/authorization/naver`로 이동

---

### 1-B. 회원가입 페이지 (`/signup`)

#### 화면 구성 전체 목록
| 요소 | 타입 | 동작 |
|---|---|---|
| 이메일 입력 | input[type=email] | 바인딩 (placeholder: alice@example.com) |
| 비밀번호 입력 | input[type=password] | 바인딩 (안내: 8자 이상, 영문/숫자/특수문자) |
| 이름 입력 | input[type=text] | 바인딩 |
| 휴대폰 입력 | input[type=tel] | 바인딩 (placeholder: 01012345678) |
| 생년월일 입력 | input[type=text] | 바인딩 (placeholder: 19900101) |
| 가입하기 버튼 | button[type=submit] | POST /api/auth/signup |
| 로그인 링크 | `<Link to="/login">` | /login 이동 |
| 처리 중... | button disabled | 요청 중 |
| 에러 메시지 | `<p>` | 실패 시 표시 |

#### 테스트 시나리오

**T2-1. 정상 가입**
1. 모든 필드 정상값 입력 후 가입하기 클릭
2. 기대: `/login`으로 이동, 성공 메시지 배너 표시

**T2-2. 이미 가입된 이메일**
1. 기존 계정 이메일로 재가입 시도
2. 기대: API 에러 메시지 표시

**T2-3. 비밀번호 규칙 위반**
- 7자: `Test12!` → 가입 클릭
- 특수문자 없음: `Test12345` → 가입 클릭
- 기대: API 측 validation 에러 메시지 표시

**T2-4. 잘못된 전화번호 형식**
1. 전화번호: `010-1234-5678` (하이픈 포함) 입력
2. 기대: API 에러 or 브라우저 validation

**T2-5. 잘못된 생년월일 형식**
1. 생년월일: `1990-01-01` (하이픈 형식) 입력
2. 기대: API 에러 메시지

**T2-6. 필수 필드 미입력**
1. 이름 비워두고 가입 클릭
2. 기대: required 속성으로 브라우저 차단

> ⚠️ **잠재 버그 확인**: 프론트엔드 signup 폼은 `email` 필드를 보내지만, 백엔드 `SignUpRequest`가 `loginId` 필드를 기대할 경우 로그인 시 email vs loginId 불일치 발생 가능. 가입 후 로그인이 실제로 성공하는지 반드시 확인.

---

### 1-C. OAuth 콜백 페이지 (`/oauth/callback`)

**테스트 시나리오**

**T3-1. 정상 OAuth 콜백**
1. 카카오/네이버 로그인 완료 후 `/oauth/callback?accessToken=...&refreshToken=...` 리다이렉트
2. 기대: "OAuth 처리 중..." 표시 → `/me` API 호출 → `/home`으로 이동

**T3-2. 토큰 파라미터 누락 콜백**
1. `/oauth/callback` 파라미터 없이 직접 접속
2. 기대: `/login`으로 즉시 리다이렉트

**T3-3. /me API 실패 시 처리**
1. 유효하지 않은 accessToken을 파라미터로 전달: `/oauth/callback?accessToken=bad&refreshToken=bad`
2. 기대: `/me` 실패 후에도 `/home`으로 이동 (catch+finally 구조)
3. 확인: `/home`에서 인증 상태가 user=null인지, 아니면 로그인 상태로 처리되는지

---

### 1-D. 인증 상태 복원 (페이지 새로고침)

**T4-1. 새로고침 후 세션 복원**
1. 로그인 상태에서 F5 새로고침
2. 기대: localStorage의 refreshToken으로 `/api/auth/refresh` 자동 호출
3. 기대: 새 accessToken 수령 후 로그인 상태 유지
4. 확인: DevTools Network 탭에서 `/api/auth/refresh` 요청 확인

**T4-2. 만료된 refreshToken으로 새로고침**
1. localStorage의 refreshToken을 임의값으로 변조 후 새로고침
2. 기대: refresh 실패 → localStorage 정리 → 로그인 페이지로 이동

**T4-3. 401 발생 시 자동 토큰 갱신 (axios interceptor)**
1. DevTools에서 accessToken을 메모리에서 삭제하는 것은 불가 → 대신 만료된 토큰을 headers에 세팅한 후 API 호출
2. 기대: 401 응답 수신 → refresh 시도 → 재요청 → 성공

**T4-4. refresh 실패 시 xrail:logout 이벤트**
1. refreshToken도 만료된 상태에서 인증 필요 API 호출
2. 기대: `window.dispatchEvent(new Event('xrail:logout'))` 발생 → user=null → /login 이동

---

### 1-E. 로그아웃

**T5-1. 정상 로그아웃 (HomePage)**
1. `/home` 헤더의 "로그아웃" 버튼 클릭
2. 기대: POST /api/auth/logout 호출
3. 기대: localStorage의 refreshToken 삭제
4. 기대: 메모리 accessToken 초기화
5. 기대: `/login`으로 이동 (RequireAuth 가드에 의해)

**T5-2. 로그아웃 후 뒤로가기 버튼**
1. 로그아웃 후 브라우저 뒤로가기
2. 기대: `/login`으로 다시 리다이렉트 (인증 가드 작동)

### 완료 체크리스트
- [ ] 정상 로그인 → /home 이동
- [ ] 잘못된 비밀번호 → 에러 메시지 표시
- [ ] 빈 폼 제출 → 브라우저 validation 차단
- [ ] 로딩 중 버튼 disabled
- [ ] 회원가입 성공 → /login + 성공 배너
- [ ] 비밀번호 규칙 위반 → API 에러
- [ ] OAuth 콜백 토큰 파라미터 누락 → /login
- [ ] 새로고침 후 세션 복원
- [ ] 만료 refreshToken → localStorage 정리 → /login
- [ ] 로그아웃 → localStorage 정리 + /login
- [ ] 로그아웃 후 뒤로가기 → /login

---

## Session 2 — 비회원 로그인

**담당 페이지:** `GuestLoginPage`  
**URL 진입점:** http://localhost:5173/guest/login  
**사용 MCP:** `chrome-devtools`, `playwright`

---

### 화면 구성 전체 목록

| 요소 | 타입 | 동작 |
|---|---|---|
| 로그인 탭 버튼 | button | tab='login' 전환, 탭 활성 스타일 |
| 신규 가입 탭 버튼 | button | tab='register' 전환, 탭 활성 스타일 |
| [로그인 탭] 액세스 코드 입력 | input | 바인딩 |
| [로그인 탭] 휴대폰 입력 | input | 바인딩 |
| [로그인 탭] 비밀번호 (4자리) 입력 | input[type=password] | 바인딩 |
| [로그인 탭] 로그인 버튼 | button[type=submit] | POST /api/auth/guest/login |
| [신규 가입 탭] 이름 입력 | input | 바인딩 |
| [신규 가입 탭] 휴대폰 입력 | input | 바인딩 |
| [신규 가입 탭] 비밀번호 (4~6자리 숫자) 입력 | input[type=password] | 바인딩 |
| [신규 가입 탭] 가입하기 버튼 | button[type=submit] | POST /api/auth/guest/register |
| 액세스 코드 표시 박스 | div | 가입 성공 후 accessCode 표시 |
| 에러 메시지 | p | 실패 시 표시 |
| 회원 로그인으로 링크 | `<Link to="/login">` | /login 이동 |

---

### 테스트 시나리오

**T6-1. 탭 전환 동작**
1. 기본 상태: 로그인 탭 활성 (파란 배경)
2. "신규 가입" 탭 클릭
3. 기대: 탭 활성 스타일 전환, 가입 폼 표시
4. "로그인" 탭 다시 클릭
5. 기대: 로그인 폼으로 복귀, 입력값 초기화 여부 확인

**T6-2. 비회원 신규 가입 정상 플로우**
1. 신규 가입 탭 선택
2. 이름: `김비회원`, 휴대폰: `01099998888`, 비밀번호: `1234` 입력
3. 가입하기 클릭
4. 기대: 초록색 박스에 **액세스 코드** 표시 ("반드시 저장해 두세요" 메시지 포함)
5. 기대: 자동으로 로그인 탭으로 전환되거나 /home으로 이동하지 않음
   > ⚠️ **UX 잠재 버그**: 가입 성공 후 로그인 탭으로 자동 전환되지 않아 사용자가 혼란을 느낄 수 있음. 실제로 자동 이동이 없는지 확인.

**T6-3. 비회원 로그인 정상 플로우**
1. 로그인 탭 선택
2. T6-2에서 발급된 액세스 코드, 휴대폰, 비밀번호 입력
3. 로그인 클릭
4. 기대: /home으로 이동, 사용자 이름 표시

**T6-4. 잘못된 액세스 코드**
1. 로그인 탭에서 액세스 코드: `INVALID123` 입력
2. 기대: API 에러 메시지 표시

**T6-5. 잘못된 비회원 비밀번호**
1. 올바른 액세스 코드, 잘못된 비밀번호 입력
2. 기대: 에러 메시지 표시

**T6-6. 비밀번호 길이 제한 확인**
1. 신규 가입 탭에서 비밀번호: `12` (2자리) 입력
2. 기대: API validation 에러 (4~6자리 숫자 제약)
3. 비밀번호: `1234567` (7자리) 입력
4. 기대: API validation 에러

**T6-7. 중복 전화번호 가입 시도**
1. 이미 가입된 전화번호로 재가입 시도
2. 기대: 에러 메시지 표시

**T6-8. 회원 로그인 링크**
1. "회원 로그인으로" 링크 클릭
2. 기대: /login으로 이동

**T6-9. 로딩 중 버튼 상태**
1. 네트워크 Slow 3G throttle 후 로그인/가입 클릭
2. 기대: "처리 중..." 텍스트 + disabled 상태

### 완료 체크리스트
- [ ] 탭 전환 스타일 정상
- [ ] 비회원 가입 → accessCode 표시
- [ ] 가입 후 UX 흐름 (자동 이동 여부) 확인
- [ ] 비회원 로그인 → /home 이동
- [ ] 잘못된 액세스 코드 → 에러
- [ ] 비밀번호 4~6자리 숫자 제약 검증
- [ ] 로딩 중 버튼 disabled

---

## Session 3 — 홈 화면 & 열차 검색

**담당 페이지:** `HomePage`  
**URL 진입점:** http://localhost:5173/home  
**사용 MCP:** `chrome-devtools` (네트워크 탭), `playwright` (edge case 자동화)

---

### 화면 구성 전체 목록

| 요소 | 타입 | 동작 |
|---|---|---|
| XRail 로고 | span | 표시만 |
| 사용자 이름 | span | AuthContext의 user.name 표시 |
| 내 예매 버튼 | button | → /reservations 이동 |
| 로그아웃 버튼 | button | AuthContext.logout() 호출 |
| 출발역 셀렉트 | select (required) | GET /api/stations 결과로 옵션 동적 로드 |
| 도착역 셀렉트 | select (required) | GET /api/stations 결과로 옵션 동적 로드 |
| 날짜 입력 | input[type=date] (required) | 기본값: 오늘 날짜 |
| 열차 검색 버튼 | button[type=submit] | GET /api/schedules?departureStationId=&arrivalStationId=&date= |
| 검색 결과 테이블 | table | schedules 배열 렌더링 |
| 열차별 선택 버튼 | button (각 행) | → /queue 이동 (state에 schedule, departureStationId, arrivalStationId 전달) |
| 에러 메시지 | p | API 에러 or 결과 없음 |
| 검색 중... | button disabled | 요청 중 |

---

### 테스트 시나리오

**T7-1. 페이지 진입 시 역 목록 자동 로드**
1. /home 접속
2. 확인: 출발역/도착역 셀렉트에 역 이름이 채워짐
3. DevTools Network 탭에서 `GET /api/stations` 자동 호출 확인

**T7-2. 정상 열차 검색**
1. 출발역: 서울 선택, 도착역: 부산 선택, 날짜: 오늘 입력
2. 열차 검색 클릭
3. 기대: 결과 테이블 표시 (열차명, 출발, 도착, 소요, 가격, 잔여석, 선택 버튼)

**T7-3. 결과 없는 날짜 검색**
1. 먼 미래 날짜(예: 2030-01-01) 또는 데이터 없는 구간 검색
2. 기대: "검색 결과가 없습니다." 에러 메시지 표시

**T7-4. 출발역 = 도착역 선택 허용 여부**
1. 출발역과 도착역을 동일하게 선택
2. 열차 검색 클릭
3. 기대: (현재 프론트 validation 없음) API 호출 후 결과 없거나 에러 반환 여부 확인
   > ⚠️ **잠재 버그**: 프론트엔드에 출발=도착 validation 없음. 사용자가 실수할 수 있음.

**T7-5. 과거 날짜 검색**
1. 날짜: `2020-01-01` 입력 후 검색
2. 기대: 결과 없음 or API 에러

**T7-6. 역 목록 로드 실패 시 셀렉트 상태**
1. auth-service/train-service 중지 후 /home 접속
2. 기대: 셀렉트가 빈 상태 (옵션 없음), 에러 메시지 표시 여부 확인
   > ⚠️ **잠재 버그**: 역 목록 로드 실패 시 에러 처리 코드가 `.catch(() => {})` — 에러 메시지 없이 빈 셀렉트만 표시됨.

**T7-7. 검색 중 로딩 상태**
1. 네트워크 Slow 3G throttle
2. 검색 클릭
3. 기대: 버튼 "검색 중..." + disabled

**T7-8. 열차 선택 버튼 → 대기열 이동**
1. 검색 결과에서 임의 열차의 "선택" 클릭
2. 기대: `/queue`로 이동
3. 기대: location.state에 `schedule`, `departureStationId`, `arrivalStationId` 포함
4. DevTools → Application → History State로 전달 값 확인

**T7-9. 잔여석 0 열차의 선택 버튼**
1. availableSeats = 0인 열차의 선택 버튼 클릭
2. 기대: (현재 코드에 disabled 처리 없음) 대기열로 이동 후 좌석 없음 확인
   > ⚠️ **잠재 버그**: 잔여석 0이어도 선택 버튼이 활성화되어 있음.

**T7-10. 내 예매 버튼**
1. 헤더의 "내 예매" 버튼 클릭
2. 기대: `/reservations`로 이동

**T7-11. 로그아웃 버튼**
1. 헤더의 "로그아웃" 클릭
2. 기대: POST /api/auth/logout → localStorage 정리 → /login 이동

**T7-12. 검색 결과 가격 포맷**
1. 검색 결과 테이블의 가격 컬럼 확인
2. 기대: `toLocaleString()` 적용으로 천단위 쉼표 표시 (예: 35,000원)

### 완료 체크리스트
- [ ] 역 목록 자동 로드
- [ ] 정상 검색 결과 테이블 표시
- [ ] 결과 없음 → "검색 결과가 없습니다." 메시지
- [ ] 출발=도착 동일 선택 시 동작 확인
- [ ] 역 목록 로드 실패 시 에러 표시 여부
- [ ] 열차 선택 → /queue + state 전달
- [ ] 잔여석 0 열차 선택 버튼 처리 확인
- [ ] 로그아웃 → /login 이동

---

## Session 4 — 대기열

**담당 페이지:** `QueuePage`, `useQueueStatus` hook  
**URL 진입점:** http://localhost:5173/queue (반드시 /home에서 열차 선택 후 진입)  
**사용 MCP:** `chrome-devtools` (EventStream 탭), `playwright` (다중 컨텍스트)

---

### 화면 구성 전체 목록

| 상태 | 요소 | 타입 | 동작 |
|---|---|---|---|
| 공통 | 스케줄 정보 박스 | div | 열차명, 역명, 시간 표시 |
| **참가 전** | 안내 문구 | p | "혼잡 시 대기열을 거칩니다" |
| **참가 전** | 대기열 참가 버튼 | button | POST /api/queue/token |
| **참가 전** | 취소 버튼 | button | → /home |
| **참가 전** | 초기 에러 메시지 | p | 진입 API 실패 시 |
| **참가 후** | 연결 방식 태그 | p | "sse" or "polling" 표시 |
| **참가 후** | 순번 숫자 | div (48px) | rank 값 |
| **참가 후** | "대기 순번" 라벨 | div | 표시 |
| **참가 후** | 전체 대기 명수 | div | totalWaiting |
| **참가 후** | 예상 대기 시간 | div | expectedWaitSeconds |
| **참가 후** | "연결 중..." | div | status=null일 때 |
| **참가 후** | "입장 처리 중..." | div | status=ACTIVE이나 아직 이동 전 |
| **참가 후** | 대기열 이탈 버튼 | button | leave() → /home |
| **참가 중** | 에러 메시지 | p | SSE/polling 에러 |

---

### 테스트 시나리오

**T8-1. /queue 직접 URL 접근 (state 없음)**
1. 주소창에 `/queue` 직접 입력
2. 기대: `/home`으로 자동 리다이렉트

**T8-2. 대기열 참가 버튼 클릭**
1. /home에서 열차 선택 → /queue 진입
2. "대기열 참가" 버튼 클릭
3. 기대: POST `/api/queue/token` 호출
4. 확인: 요청 헤더에 `X-Captcha-Token: c_stub:0000`, `Idempotency-Key: <UUID>` 포함 (DevTools)

**T8-3. 즉시 ACTIVE 응답 (대기 없이 통과)**
1. 대기열 진입 API 응답이 `status: "ACTIVE"`일 때
2. 기대: QueuePage 대기 화면 미표시, 바로 `/seats`로 이동
3. 기대: location.state에 `queueToken` 포함

**T8-4. 대기 WAITING 상태 표시**
1. 대기열에 진입하고 WAITING 상태
2. 기대: 파란 큰 숫자(rank), "전체 대기: N명", "예상 대기: 약 Ns" 표시

**T8-5. SSE 연결 방식 확인**
1. 대기열 참가 후 참가 상태 진입
2. DevTools Network 탭 → EventStream 필터
3. 기대: `/api/queue/subscribe?scope=global` SSE 연결 표시
4. 기대: 연결 방식 태그가 "sse"로 표시

**T8-6. SSE 에러 2회 → polling fallback 자동 전환**
1. 대기열 참가 후 네트워크를 일시 차단 (DevTools → Offline)
2. 잠시 후 네트워크 복구
3. 기대: SSE 에러 2회 발생 후 연결 방식 태그가 "polling"으로 변경
4. 기대: polling 모드에서 2초 간격으로 GET `/api/queue/status?scope=global` 호출 (DevTools 확인)

**T8-7. ACTIVE 이벤트 수신 → /seats 자동 이동**
1. 대기열 WAITING 중 대기가 끝나 ACTIVE 이벤트 수신
2. 기대: SSE 연결 자동 종료
3. 기대: `/seats`로 자동 이동
4. 기대: location.state에 `queueToken` 전달

**T8-8. 대기열 이탈 버튼**
1. WAITING 상태에서 "대기열 이탈" 클릭
2. 기대: POST `/api/queue/leave` 호출
3. 기대: SSE/polling 중지
4. 기대: `/home`으로 이동

**T8-9. 대기열 참가 중 에러 처리**
1. queue-service 중지 후 "대기열 참가" 클릭
2. 기대: initError 메시지 표시 ("대기열 진입 실패" 또는 API 에러 메시지)
3. 기대: 참가 전 UI 유지 (참가 후 UI로 전환되지 않음)

**T8-10. 취소 버튼 (참가 전)**
1. "취소" 버튼 클릭
2. 기대: `/home`으로 이동 (API 호출 없음)

**T8-11. 참가 중 버튼 상태**
1. 대기열 참가 클릭 후 응답 전
2. 기대: "참가 중..." + disabled

**T8-12. 다중 사용자 동시 대기열 진입 (Playwright)**
```javascript
const ctx1 = await browser.newContext();
const ctx2 = await browser.newContext();
// 각각 로그인 후 동일 scheduleId로 대기열 진입
// 기대: rank가 1, 2로 순서대로 발급
```

### 완료 체크리스트
- [x] /queue 직접 접근 → /home 리다이렉트
- [x] 대기열 참가 요청 헤더 포함 (Captcha, Idempotency-Key)
- [x] 즉시 ACTIVE → /seats 이동
- [x] WAITING 상태 UI 표시 (rank, totalWaiting, expectedWaitSeconds)
- [x] SSE EventStream 연결 확인
- [x] SSE 2회 에러 → polling 전환 (코드 로직 확인, Gateway keepalive 특성상 브라우저 직접 트리거 불가)
- [x] ACTIVE 이벤트 → /seats 자동 이동
- [x] 대기열 이탈 → API 호출 + /home 이동
- [x] 진입 실패 에러 메시지 표시

### 발견 및 수정된 버그 (S4)
- **[FIXED] CRITICAL** `api-gateway/application.yml` — `metadata.response-timeout: 0` (0ms 즉시 타임아웃) → SSE 전용 라우트 분리 + `response-timeout: -1`, 일반 queue 라우트는 글로벌 5s 적용
- **[FIXED] HIGH** `useQueueStatus.ts` + `JwtValidationFilter.java` — SSE `EventSource`는 Authorization 헤더 전송 불가 → SSE URL에 `token` 쿼리 파라미터 추가, Gateway 필터에서 `/api/queue/subscribe` 경로에 한해 `token` 쿼리 파라미터 허용
- **[FIXED] HIGH** `QueueController.java` — `@DeleteMapping("/leave")` → `@PostMapping("/leave")` (가이드/프론트 명세 일치)

---

## Session 5 — 좌석 선택 & 예약

**담당 페이지:** `SeatPage`  
**URL 진입점:** http://localhost:5173/seats (반드시 /queue → ACTIVE 통과 후 진입)  
**사용 MCP:** `playwright` (동시 선택 시나리오), `chrome-devtools` (요청 헤더 검사)

---

### 화면 구성 전체 목록

| 요소 | 타입 | 동작 |
|---|---|---|
| 열차 정보 텍스트 | p | 열차명, 구간, 시간 |
| 에러 메시지 | p | API 에러 or 유효성 오류 |
| 좌석 로딩 중... | p | GET /api/schedules/{id}/seats 완료 전 |
| 칸 번호 라벨 | strong | "{N}호 칸" |
| 좌석 버튼 (가용) | button (초록) | 클릭 시 선택(파란)/해제(초록) 토글 |
| 좌석 버튼 (불가) | button (회색) | disabled, cursor:not-allowed |
| 선택 요약 라벨 | span | "선택: N석 \| 합계: M원" |
| 예약하기 버튼 | button | POST /api/reservations (selected.size > 0일 때만 활성) |

---

### 테스트 시나리오

**T9-1. /seats 직접 URL 접근 (state 없음)**
1. 주소창에 `/seats` 직접 입력
2. 기대: `/home`으로 자동 리다이렉트

**T9-2. 좌석 목록 로드 확인**
1. /queue → ACTIVE → /seats 진입
2. 기대: 칸별 좌석 그리드 렌더링
3. 기대: 가용 좌석 초록색, 불가 좌석 회색 표시
4. DevTools Network에서 GET `/api/schedules/{scheduleId}/seats` 파라미터 확인 (departureStationId, arrivalStationId)

**T9-3. 좌석 선택/해제 토글**
1. 가용 좌석 클릭 → 파란색으로 변경 (선택됨)
2. 동일 좌석 다시 클릭 → 초록색 복귀 (해제)
3. 기대: 하단 "선택: N석 | 합계: M원" 실시간 업데이트

**T9-4. 불가 좌석 클릭 무반응**
1. 회색(불가) 좌석 클릭
2. 기대: 아무런 상태 변화 없음 (disabled)

**T9-5. 여러 좌석 동시 선택**
1. 여러 가용 좌석 클릭
2. 기대: 모두 파란색, 선택 수 및 합계 금액 누적 표시

**T9-6. 예약하기 버튼 활성/비활성 조건**
1. 좌석 미선택 상태: 기대 → 예약하기 버튼 disabled
2. 좌석 1개 선택: 기대 → 예약하기 버튼 활성화

**T9-7. 좌석 미선택 상태로 예약하기 시도**
1. 좌석을 선택하지 않은 상태에서 예약하기 클릭 (disabled이지만 직접 DOM 조작 또는 JS 호출)
2. 기대: `setError('좌석을 선택하세요.')` 에러 메시지 표시

**T9-8. 정상 예약**
1. 좌석 선택 후 예약하기 클릭
2. 기대: POST `/api/reservations` 요청
3. 확인: 요청 헤더에 `X-Queue-Token` + `Idempotency-Key` 포함 (DevTools)
4. 기대: 성공 시 `/payment`로 이동, location.state에 `reservation` 전달

**T9-9. SEAT_ALREADY_TAKEN 에러 처리**
1. 다른 사용자가 먼저 예약한 좌석 선택 후 예약하기 클릭
2. 기대: 에러 메시지 "선택한 좌석이 이미 예약되었습니다. 다른 좌석을 선택하세요."
3. 기대: 선택 초기화 (`setSelected(new Set())`)
4. 기대: 좌석 맵 자동 새로고침 (GET `/api/schedules/{id}/seats` 재호출)
5. 기대: 해당 좌석이 회색(불가)으로 표시됨

**T9-10. 동시 예약 경합 (Playwright)**
```javascript
// 2개 컨텍스트가 동일 좌석 선택 후 동시에 예약
const [r1, r2] = await Promise.all([
  ctx1.request.post('/api/reservations', { data: body1 }),
  ctx2.request.post('/api/reservations', { data: body2 }),
]);
// 하나만 성공, 나머지는 SEAT_ALREADY_TAKEN 또는 에러
```

**T9-11. 예약 중 로딩 상태**
1. 예약하기 클릭 후 응답 전
2. 기대: 버튼 "예약 중..." + disabled

**T9-12. queueToken 없이 예약 요청 (직접 API)**
```bash
curl -X POST http://localhost:8080/api/reservations \
  -H 'Authorization: Bearer <토큰>' \
  -H 'Idempotency-Key: <UUID>' \
  # X-Queue-Token 없음
```
기대: 403 또는 401 반환

**T9-13. 좌석 가격 tooltip 확인**
1. 좌석 버튼에 마우스 호버
2. 기대: `title` 속성 "A1 — 35,000원" 형식 tooltip 표시

**T9-14. 페이지 이탈 후 재진입 (뒤로가기)**
1. /seats에서 예약하기 완료 후 /payment에서 뒤로가기
2. 기대: /seats 재진입 시 좌석 목록 재로드 (useEffect 재실행)

### 완료 체크리스트
- [ ] /seats 직접 접근 → /home
- [ ] 좌석 목록 가용/불가 색상 구분
- [ ] 선택/해제 토글 + 합계 금액 실시간 업데이트
- [ ] 불가 좌석 클릭 무반응
- [ ] 좌석 미선택 시 예약하기 버튼 disabled
- [ ] 정상 예약 → /payment 이동
- [ ] X-Queue-Token 헤더 포함 확인
- [ ] SEAT_ALREADY_TAKEN → 에러 메시지 + 좌석 맵 새로고침
- [ ] 예약 중 로딩 상태

---

## Session 6 — 결제

**담당 페이지:** `PaymentPage`  
**URL 진입점:** http://localhost:5173/payment (반드시 /seats에서 예약 성공 후 진입)  
**사용 MCP:** `playwright` (이중 결제 자동화), `chrome-devtools` (응답/헤더 검사)

---

### 화면 구성 전체 목록

| 요소 | 타입 | 동작 |
|---|---|---|
| 예약 정보 박스 | div | 예약번호, 좌석, 결제금액, 만료 시간 표시 |
| 만료 시간 텍스트 | div | expiresIn < 120초 시 빨간색 (#d32f2f) |
| 신용카드 라디오 | input[type=radio] | method='CARD' 선택 |
| 계좌이체 라디오 | input[type=radio] | method='TRANSFER' 선택 |
| N원 결제하기 버튼 | button | POST /api/payments |
| 취소 버튼 | button | → /home |
| 에러 메시지 | p | 결제 실패 or RATE_LIMITED 표시 |
| 결제 중... | button disabled | 요청 중 |

---

### 테스트 시나리오

**T10-1. /payment 직접 URL 접근 (state 없음)**
1. 주소창에 `/payment` 직접 입력
2. 기대: `/home`으로 자동 이동

**T10-2. 예약 정보 표시 확인**
1. /seats에서 예약 성공 후 /payment 진입
2. 확인: 예약번호, 좌석번호, 결제금액 표시
3. 확인: 결제금액에 천단위 쉼표 포맷 (`toLocaleString()`)
4. 확인: 만료 시간 표시 ("N분 M초 남음")

**T10-3. 만료 시간 2분 미만 시 빨간색**
1. DB에서 `reservations.expires_at`을 현재 + 1분 30초로 설정 후 /payment 접속
2. 기대: 만료 시간 텍스트가 빨간색 (#d32f2f) 으로 표시
   > ⚠️ **잠재 버그**: 만료 시간이 페이지 로드 시 1회만 계산됨 (`Date.now()` 호출이 useState/useRef 아님). 카운트다운이 실시간으로 갱신되지 않을 수 있음. 30초 대기 후 만료 시간 숫자 변화 여부 확인.

**T10-4. 결제 방법 선택 (라디오 버튼)**
1. 기본 선택: 신용카드 (CARD)
2. "계좌이체" 라디오 클릭
3. 기대: 계좌이체 선택됨, 신용카드 해제
4. 확인: 결제 요청 시 `method: "TRANSFER"` 전송

**T10-5. 신용카드로 정상 결제**
1. 신용카드 선택 상태로 "N원 결제하기" 클릭
2. 기대: POST `/api/payments` 요청
3. 확인: 요청 헤더에 `Idempotency-Key` 포함 (DevTools)
4. 기대: `status: "COMPLETED"` 응답 시 `/reservations`로 이동
5. 기대: location.state에 `justPaid: true` 전달

**T10-6. 계좌이체로 정상 결제**
1. 계좌이체 선택 후 결제하기 클릭
2. 기대: `method: "TRANSFER"` 포함 요청 → 결제 완료 → /reservations

**T10-7. 결제 실패 처리**
1. payment-service를 임시 중지하거나 결제 API 강제 에러 유발
2. 기대: 에러 메시지 표시, 페이지 유지

**T10-8. RATE_LIMITED 에러 처리**
1. 결제 API를 빠르게 여러 번 호출 (Playwright로 반복)
2. 기대: 429 응답 시 "요청이 너무 많습니다. N초 후 재시도 하세요." 메시지
3. 확인: `Retry-After` 헤더 값이 메시지에 반영됨

**T10-9. 이중 결제 방지 (Playwright)**
```javascript
// 동일 idempotencyKey로 결제 2회 동시 호출
const [r1, r2] = await Promise.all([
  page.request.post('/api/payments', { data: paymentBody, headers: { 'Idempotency-Key': key } }),
  page.request.post('/api/payments', { data: paymentBody, headers: { 'Idempotency-Key': key } }),
]);
// 하나만 200, 나머지는 409 또는 캐시 응답
```

**T10-10. 취소 버튼**
1. "취소" 버튼 클릭
2. 기대: `/home`으로 이동 (결제 취소, API 미호출)
3. 확인: 예약 상태가 PENDING으로 남아있어 /reservations에서 재결제 가능한지 확인

**T10-11. 결제 완료 후 /reservations 도착 확인**
1. 결제 성공 후 자동 이동된 /reservations 화면
2. 기대: justPaid state 처리 여부 확인 (현재 코드에서 특별 UI 없음 — 확인 필요)
3. 확인: 방금 결제한 예약의 상태가 "결제 완료"로 표시

**T10-12. 결제 중 로딩 상태**
1. 네트워크 Slow 3G throttle 후 결제하기 클릭
2. 기대: "결제 중..." + disabled

### 완료 체크리스트
- [ ] /payment 직접 접근 → /home
- [ ] 예약 정보 정확히 표시
- [ ] 만료 2분 미만 → 빨간색
- [ ] 만료 시간 실시간 카운트다운 여부 확인
- [ ] 신용카드/계좌이체 선택 → method 파라미터 반영
- [ ] 결제 성공 → /reservations (justPaid: true)
- [ ] Idempotency-Key 헤더 포함 확인
- [ ] RATE_LIMITED 에러 메시지 (Retry-After 반영)
- [ ] 이중 결제 → 409
- [ ] 취소 버튼 → /home (예약 PENDING 유지)

---

## Session 7 — 예매 내역

**담당 페이지:** `ReservationsPage`  
**URL 진입점:** http://localhost:5173/reservations  
**사용 MCP:** `chrome-devtools`, `playwright`

---

### 화면 구성 전체 목록

| 요소 | 타입 | 동작 |
|---|---|---|
| "내 예매 내역" h2 | h2 | 표시 |
| ← 홈으로 버튼 | button | → /home |
| 에러 메시지 | p | 조회 실패 시 |
| 로딩 중... | p | 데이터 로드 중 |
| "예매 내역이 없습니다." | p | content 빈 배열 시 |
| 예약 카드 (각 항목) | div | reservations.map() |
| 예약 번호 | span | `#reservationId` |
| 예약 상태 | span | "결제 대기" / "결제 완료" / "취소" (색상 포함) |
| 좌석 | div | tickets[].seatNumber join |
| 금액 | div | toLocaleString() + "원" |
| 예약일 | div | toLocaleString('ko-KR') |
| 만료 시간 (PENDING) | div | expiresAt 있을 때만 표시, 주황색 |
| 예약 취소 버튼 | button | PENDING or PAID 상태에서 표시, confirm 후 DELETE |
| 결제하기 버튼 | button | PENDING 상태에서만 표시, → /payment |

---

### 테스트 시나리오

**T11-1. 예매 내역 페이지 로드**
1. /reservations 접속
2. 기대: GET `/api/reservations?page=0&size=20` 자동 호출 (DevTools 확인)
3. 기대: 예약 목록 카드 형식으로 표시

**T11-2. 예약 없을 때 빈 상태**
1. 아직 예약이 없는 계정으로 /reservations 접속
2. 기대: "예매 내역이 없습니다." 중앙 정렬 텍스트 표시

**T11-3. 상태별 표시 확인**
| 상태 | 라벨 | 색상 |
|---|---|---|
| PENDING | 결제 대기 | 주황 (#f57c00) |
| PAID | 결제 완료 | 초록 (#388e3c) |
| CANCELLED | 취소 | 회색 (#9e9e9e) |

1. 각 상태의 예약을 조회하여 라벨과 색상이 일치하는지 확인

**T11-4. PENDING 예약 → 예약 취소 버튼 표시**
1. PENDING 상태 예약 카드 확인
2. 기대: "예약 취소" 버튼 + "결제하기" 버튼 동시 표시

**T11-5. PAID 예약 → 예약 취소 버튼만 표시**
1. PAID 상태 예약 카드 확인
2. 기대: "예약 취소" 버튼만 표시, "결제하기" 버튼 없음

**T11-6. CANCELLED 예약 → 버튼 없음**
1. CANCELLED 상태 예약 카드 확인
2. 기대: 취소/결제 버튼 모두 없음

**T11-7. 예약 취소 — confirm 대화상자**
1. "예약 취소" 버튼 클릭
2. 기대: `confirm('예약을 취소하시겠습니까?')` 브라우저 대화상자 표시
3. **"취소" 클릭** 시: 아무 동작 없음, 목록 유지
4. **"확인" 클릭** 시: DELETE `/api/reservations/{id}` 호출

**T11-8. 예약 취소 성공 후 목록 새로고침**
1. 예약 취소 확인 클릭
2. 기대: DELETE API 성공 후 `load()` 재호출
3. 기대: 해당 예약이 취소 상태로 변경 (또는 사라짐)

**T11-9. 예약 취소 실패 처리**
1. train-service 중지 후 취소 클릭
2. 기대: `alert(msg ?? '취소 실패')` 브라우저 alert 표시

**T11-10. 취소 중 로딩 상태**
1. Slow 3G throttle 후 취소 확인
2. 기대: 해당 예약 카드의 버튼 "취소 중..." + disabled
3. 기대: 다른 예약 카드의 버튼은 정상 상태 유지 (`cancelling === reservationId` 조건)

**T11-11. PENDING 예약 → 결제하기 버튼 → 결제 페이지 이동**
1. PENDING 예약 카드의 "결제하기" 버튼 클릭
2. 기대: `/payment`로 이동
3. 기대: location.state에 해당 `reservation` 전달

**T11-12. PENDING 예약 만료 시간 표시**
1. PENDING + expiresAt 있는 예약 카드 확인
2. 기대: "만료: YYYY. M. D. 오전/오후 H:MM:SS" 형식 표시 (toLocaleString('ko-KR'))
3. 기대: 텍스트 색상 주황 (#f57c00)

**T11-13. ← 홈으로 버튼**
1. "← 홈으로" 클릭
2. 기대: `/home`으로 이동

**T11-14. justPaid state로 진입 시 처리**
1. 결제 성공 후 /reservations에 `justPaid: true` state로 진입
2. 현재 코드에서 justPaid에 대한 특별 UI 없음
   > ⚠️ **잠재 버그**: "결제가 완료되었습니다!" 같은 성공 배너가 없어 사용자가 결제 성공 여부를 직관적으로 인지하지 못할 수 있음.

**T11-15. 알 수 없는 status 코드 처리**
1. DB에서 직접 `reservations.status = 'EXPIRED'`로 변경
2. /reservations 조회
3. 기대: `STATUS_LABEL[r.status] ?? r.status` 로직에 의해 "EXPIRED" 원문 표시
4. 기대: 버튼 없음 (PENDING/PAID 아니므로)

### 완료 체크리스트
- [ ] 예매 내역 GET 자동 호출
- [ ] 빈 상태 메시지
- [ ] 상태별 라벨/색상 일치
- [ ] PENDING → 취소+결제 버튼
- [ ] PAID → 취소 버튼만
- [ ] CANCELLED → 버튼 없음
- [ ] 취소 confirm 대화상자 → 취소 시 아무 동작 없음
- [ ] 취소 성공 → 목록 새로고침
- [ ] 취소 실패 → alert 메시지
- [ ] 취소 중 해당 버튼만 disabled
- [ ] 결제하기 → /payment + reservation state 전달
- [ ] justPaid 진입 시 피드백 UI 확인

---

## Session 8 — 관측성 & Swagger & 전체 E2E

**담당:** Prometheus, Grafana, Zipkin, Swagger 전체 검증, 전체 사용자 여정 E2E  
**사용 MCP:** `chrome-devtools` (navigate + 스크린샷), `playwright` (전체 E2E 자동화)

---

### 8-A. 전체 사용자 여정 E2E (Happy Path)

```
회원가입 → 로그인 → 열차 검색 → 열차 선택
→ 대기열 진입 → ACTIVE → 좌석 선택 → 예약
→ 결제 → 예매 내역 확인 → 예약 취소 → 로그아웃
```

**T12-1. E2E Playwright 전체 플로우**
```javascript
// 1. 회원가입
await page.goto('http://localhost:5173/signup');
await page.fill('input[type=email]', 'e2e@test.com');
await page.fill('input[type=password]', 'Test1234!');
// ... 나머지 필드

// 2. 로그인
await page.goto('http://localhost:5173/login');
await page.fill('input[type=email]', 'e2e@test.com');
await page.fill('input[type=password]', 'Test1234!');
await page.click('button[type=submit]');
await page.waitForURL('**/home');

// 3. 열차 검색
await page.selectOption('select:first-of-type', { label: '서울' }); // 출발
await page.selectOption('select:last-of-type', { label: '부산' });  // 도착
await page.click('button[type=submit]');
await page.waitForSelector('table');

// 4. 열차 선택
await page.click('button:has-text("선택")');
await page.waitForURL('**/queue');

// 5. 대기열 참가 & ACTIVE 대기
await page.click('button:has-text("대기열 참가")');
await page.waitForURL('**/seats', { timeout: 120000 }); // 최대 2분 대기

// 6. 좌석 선택 & 예약
await page.click('button[style*="c8e6c9"]:first-of-type'); // 첫 번째 가용 좌석
await page.click('button:has-text("예약하기")');
await page.waitForURL('**/payment');

// 7. 결제
await page.click('button:has-text("결제하기")');
await page.waitForURL('**/reservations');

// 8. 예매 내역 확인
await page.waitForSelector('span:has-text("결제 완료")');

// 9. 예약 취소
page.on('dialog', dialog => dialog.accept());
await page.click('button:has-text("예약 취소")');
await page.waitForSelector('span:has-text("취소")');

// 10. 로그아웃
await page.goto('/home');
await page.click('button:has-text("로그아웃")');
await page.waitForURL('**/login');
```

---

### 8-B. 관측성

**T13-1. 각 서비스 Prometheus 엔드포인트**
```bash
for port in 8081 8082 8084 8085 8086; do
  echo "=== :$port ===" && curl -s http://localhost:$port/actuator/prometheus | head -3
done
```

**T13-2. xrail 도메인 메트릭 존재 확인**
```bash
for port in 8081 8082 8085 8086; do
  echo "=== :$port ===" && curl -s http://localhost:$port/actuator/prometheus | grep "xrail_"
done
```

**T13-3. Prometheus targets UP 확인**
1. http://localhost:9090/targets 접속
2. 기대: 모든 서비스 UP, Last Scrape 시간 현재에 근접

**T13-4. Grafana 대시보드 5패널 확인**
1. http://localhost:3000 (admin/admin)
2. XRail MSA Dashboard → 5패널 데이터 표시 확인

**T13-5. Zipkin 분산 추적**
1. http://localhost:9411 접속
2. E2E 플로우의 traceId로 검색
3. train-service → payment-service → notification-service 스팬 연결 확인

---

### 8-C. Swagger UI 검증

**T14-1. 5개 서비스 Swagger 접근 (Playwright 순회)**
```javascript
const services = [
  { port: 8081, name: 'auth' },
  { port: 8082, name: 'train' },
  { port: 8084, name: 'queue' },
  { port: 8085, name: 'payment' },
  { port: 8086, name: 'notification' },
];
for (const svc of services) {
  await page.goto(`http://localhost:${svc.port}/swagger-ui/index.html`);
  await page.screenshot({ path: `swagger-${svc.name}.png` });
}
```

**T14-2. 모든 응답이 ApiResponse envelope**
```json
// 성공
{ "success": true, "code": "...", "data": {...}, "message": null }
// 실패
{ "success": false, "code": "ERROR_CODE", "data": null, "message": "..." }
```
Swagger에서 Try It Out 실행 후 응답 구조 확인.

**T14-3. 서킷브레이커 상태 확인**
```bash
curl http://localhost:8080/actuator/circuitbreakers
# 5개 CB 모두 CLOSED 확인
```

### 완료 체크리스트
- [ ] 전체 E2E 플로우 Playwright로 완주
- [ ] 5개 서비스 Prometheus 응답
- [ ] xrail.* 메트릭 존재
- [ ] Grafana 5패널 데이터
- [ ] Zipkin 분산 추적 연결
- [ ] 5개 서비스 Swagger UI 정상
- [ ] 모든 API 응답 ApiResponse envelope
- [ ] 서킷브레이커 5개 CLOSED

---

## 세션 간 버그 전달 프로토콜

1. 버그 발견 세션 → 위 **버그 보고 형식**으로 작성
2. 심각도 CRITICAL/HIGH → 즉시 코드 수정 담당에 전달
3. 심각도 MEDIUM/LOW → 세션 완료 후 일괄 취합
4. 코드 수정 완료 → 발견 세션에서 재검증 후 체크리스트 항목 완료

## 잠재 버그 사전 목록 (코드 분석 결과)

| # | 위치 | 내용 | 심각도 |
|---|---|---|---|
| P1 | `LoginPage` | 프론트는 `email` 필드 전송, 백엔드 `SignUpRequest`가 `loginId` 기대할 경우 불일치 | HIGH |
| P2 | `HomePage` | 출발역 = 도착역 선택 시 프론트 validation 없음 | MEDIUM |
| P3 | `HomePage` | 역 목록 로드 실패 시 `.catch(() => {})` — 에러 메시지 없음 | MEDIUM |
| P4 | `HomePage` | 잔여석 0 열차의 선택 버튼 disabled 처리 없음 | MEDIUM |
| P5 | `GuestLoginPage` | 비회원 가입 성공 후 로그인 탭 자동 전환 없음 | LOW |
| P6 | `PaymentPage` | 만료 시간이 렌더 시 1회 계산, 실시간 카운트다운 없음 | MEDIUM |
| P7 | `ReservationsPage` | justPaid state 수신 시 성공 피드백 UI 없음 | LOW |
| P8 | `OAuthCallbackPage` | /me 실패 시에도 /home 이동 (로그인 상태 불명확) | MEDIUM |

## 참고 문서

- `docs/ARCHITECTURE.md` — 서비스 토폴로지, Kafka 이벤트 흐름도
- `docs/ERD.md` — 스키마 상세, Redis 키 패턴 §5
- `docs/API.md` — REST/Kafka 계약 명세
- `docs/SESSION_CONTEXT.md` — 이전 세션 수정 이력
- 루트 `CLAUDE.md` — P1~P8 아키텍처/코딩 규칙
- 각 서비스 `CLAUDE.md` — G1~G7, A1~A6, T1~T7, Q1~Q7
