# common-lib — 소규칙

> 대규칙: 루트 `CLAUDE.md` 참조. 이 파일은 common-lib 작업 시 추가로 지킬 규칙이다.

## 역할

모든 서비스가 공유하는 **라이브러리**. 실행 가능한 Spring Boot 앱이 아니다.

## 규칙

### L1. 범위 제한
- 특정 서비스의 비즈니스 로직 절대 포함 금지. "train-service에서만 쓰이는데 여기 놓으면 편하겠다" → 해당 서비스 내부에 둬라.
- Spring Boot `@SpringBootApplication`, `@Component`, `@Service` 사용 금지. 순수 라이브러리 클래스만.
- `@EnableJpaAuditing` 같은 컨텍스트 활성화 애노테이션 금지 — 각 서비스가 직접 선언한다.

### L2. 이벤트 record 규칙
- Kafka 이벤트는 Java `record`로 정의. 필드 추가 시 **기존 필드 순서 변경 금지** (역직렬화 호환성).
- 필드 추가는 마지막에 nullable로 추가. 필드 제거는 deprecated 처리 후 다음 마일스톤에서 제거.
- 공통 필드 `eventId`, `occurredAt`, `traceId`는 모든 이벤트 record에 반드시 포함.

### L3. ErrorCode 관리
- `ErrorCode` enum에 새 에러 추가 시 HTTP 상태코드, 코드명, 메시지 세 가지 모두 명시.
- 기존 `code` 문자열 값 변경 금지 — API 클라이언트가 이 값으로 분기한다.

### L4. 의존성 최소화
- 새 외부 라이브러리 추가 전 반드시 확인: 이 의존성이 모든 서비스에 전파된다.
- `build.gradle`에서 `api` scope는 실제로 타 서비스 코드에서 직접 사용하는 것만. 내부용은 `implementation`.

### L5. 하위호환성
- 이미 배포된 서비스들이 이 라이브러리를 참조한다. 기존 public API(클래스명, 메서드 시그니처) 변경 시 모든 서비스 컴파일 확인.
