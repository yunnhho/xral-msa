# discovery-server — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 discovery-server 작업 시 추가로 지킬 규칙이다.

## 역할

Eureka 서버. 서비스 레지스트리 역할만 한다.

## 규칙

### D1. 비즈니스 로직 금지
- 이 서비스에는 Controller, Service, Repository 추가 금지.
- `application.yml` 설정과 `@EnableEurekaServer` 외의 코드 변경은 명시적 요청이 있을 때만.

### D2. 설정 변경 시 주의
- `eureka.server.wait-time-in-ms-when-sync-empty=0` 제거 금지 — 로컬 기동 시 대기 시간 제거용.
- `register-with-eureka: false` / `fetch-registry: false` 제거 금지 — Eureka 서버가 자기 자신에 등록하는 것을 막는다.
- docker 프로파일의 hostname은 `discovery-server` 고정 (docker-compose 컨테이너명과 일치).

### D3. 포트
- 서버 포트 `8761` 고정. 변경 시 docker-compose.yml과 모든 서비스의 `EUREKA_DEFAULT_ZONE` 환경변수도 함께 변경.
