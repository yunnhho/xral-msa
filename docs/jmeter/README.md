# XRail JMeter 부하 테스트

## 시나리오

1,000 동시 사용자가 다음 순서로 요청:

1. **Signup** — 고유 아이디로 회원가입 → `accessToken` 추출
2. **Schedule Search** — 출발/도착/날짜로 열차 검색 → `scheduleId` 추출
3. **Queue Token** — 대기열 진입 → `queueToken` 추출 (WAITING이면 polling)
4. **Seat Availability** — 가용 좌석 조회 → 첫 번째 빈 좌석 `seatId` 추출
5. **Reserve** — 좌석 예약 → `reservationId`, `totalPrice` 추출
6. **Pay** — 결제 완료

## 사전 준비

```bash
# 1. 전체 스택 기동 (docker-compose)
docker-compose up -d

# 2. DB에 테스트용 스케줄 및 좌석 데이터 삽입 확인
#    최소 1개 이상의 스케줄과 충분한 좌석이 있어야 함

# 3. results 디렉토리 생성
mkdir -p docs/jmeter/results
```

## 실행

```bash
# GUI 없이 CLI 실행 (권장)
jmeter -n \
  -t docs/jmeter/xrail-load-test.jmx \
  -l docs/jmeter/results/xrail-results.csv \
  -e -o docs/jmeter/results/html-report \
  -JSCHEDULE_ID=1 \
  -JDEPARTURE_STATION_ID=1 \
  -JARRIVAL_STATION_ID=6

# 결과 HTML 리포트 확인
open docs/jmeter/results/html-report/index.html
```

## 파라미터 재정의

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `BASE_URL` | `localhost` | Gateway 호스트 |
| `BASE_PORT` | `8080` | Gateway 포트 |
| `SCHEDULE_ID` | `1` | fallback 스케줄 ID (검색 결과 없을 때) |
| `DEPARTURE_STATION_ID` | `1` | 출발역 ID |
| `ARRIVAL_STATION_ID` | `6` | 도착역 ID |

## 목표 지표

| 지표 | 목표 |
|------|------|
| p95 응답시간 (예약) | < 800ms |
| p50 응답시간 (조회) | < 100ms |
| 오류율 | < 1% |
| 좌석 중복 예약 | 0건 |

## 결과 확인 방법

- **HTML 리포트**: `results/html-report/index.html`
- **CSV 원본**: `results/xrail-results.csv`
- **Grafana**: `http://localhost:3000` — xrail-dashboard
- **Zipkin**: `http://localhost:9411` — 분산 트레이스
