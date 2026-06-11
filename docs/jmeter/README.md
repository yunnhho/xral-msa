# XRail JMeter 부하 테스트

## 테스트 플랜 2종

| 파일 | 목적 |
|------|------|
| `xrail-load-test.jmx` | 1,000 동접 메인 시나리오: login → search → queue → seats → reserve → pay → leave |
| `xrail-overbook-test.jmx` | 100 스레드 동일 좌석 동시 예약(SyncTimer rendezvous) — overbooking 0 검증 |

계정 가입은 측정 시나리오에서 제외(PRD M23: search→reserve→pay가 측정 대상). 가입은 사전 시드로 처리한다.

## 사전 준비

```bash
# 1. 인프라 + 전체 서비스 기동. 부하 측정용 플래그:
#    - gateway:  -Drate-limit.enabled=false        (IP 단위 리미터 — 단일 IP 부하기와 양립 불가)
#    - payment:  -Dpayment.mock.failure-rate=0     (MockPG 랜덤 실패 10% → 0)
#    - auth:     -Dauth.bcrypt.strength=10         (기본 12 — 단일 머신에서 1,000 로그인 시 CPU 한계. 하한 10 강제됨)

# 2. 부하 테스트 계정 1,000개 시드 → users.csv 생성 (멱등)
python3 docs/jmeter/seed-users.py 1000

# 3. 스케줄 데이터는 train-service 부팅 시 자동 백필(오늘~+30일, 일 9편)
```

> **주의**: 서비스 재기동 직후 바로 테스트하지 말 것 — Eureka DOWN→UP 전파/게이트웨이 LB 캐시(약 30s) 과도기에 "No servers available" 503 + 서킷브레이커 오픈이 발생한다. 스모크(20 users)로 안정 확인 후 본 테스트 실행.

## 실행

```bash
# 스모크 (시나리오 검증)
jmeter -n -t docs/jmeter/xrail-load-test.jmx -l docs/jmeter/results/smoke.csv -JUSERS=20 -JRAMPUP=5

# 본 테스트 (1,000 동접, HTML 리포트)
HEAP="-Xms1g -Xmx2g" jmeter -n \
  -t docs/jmeter/xrail-load-test.jmx \
  -l docs/jmeter/results/run.csv \
  -e -o docs/jmeter/results/html-report \
  -JUSERS=1000 -JRAMPUP=60

# 동일좌석 100스레드 overbooking 테스트
jmeter -n -t docs/jmeter/xrail-overbook-test.jmx -l docs/jmeter/results/overbook.csv
```

## 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `HOST` / `PORT` | `localhost` / `8080` | Gateway |
| `USERS` / `RAMPUP` | `1000` / `60` | 메인 시나리오 스레드/램프업(초) |
| `DEPARTURE_STATION_ID` | `1` (서울) | 노선 1: 서울(1)→대전(2)→동대구(3)→부산(4) |
| `ARRIVAL_STATION_ID` | `4` (부산) | |
| `OB_DATE` | 내일 | overbook 테스트 타깃 날짜 |

## 시나리오 세부

- **CAPTCHA**: 게이트웨이 스텁은 `base64(timestamp)` 1회용 토큰(±30s) — JSR223이 요청 직전 생성, 스레드별 leading-zero 패딩으로 유니크 보장.
- **검색 분산**: 날짜를 내일~+29일에서 랜덤, 검색 결과에서 스케줄 랜덤 선택, 가용 좌석 중 랜덤 선택(JSONPostProcessor match_numbers=0).
- **대기열**: WAITING이면 3초 간격 폴링(최대 60회). 비-2xx면 즉시 중단. 결제 후 `POST /api/queue/leave`로 active 슬롯 반납.
- **로그인 실패 시** 이후 전 스텝 스킵(오류 연쇄 방지).

## 목표 지표 및 최종 결과 (2026-06-11, M1 Mac 10코어 로컬 풀스택)

| 지표 | 목표 | 결과 |
|------|------|------|
| p95 응답시간 (예약) | < 800ms | **14ms** |
| p50 응답시간 (조회) | < 100ms | **11ms** |
| 오류율 | < 1% | **0%** (7,000 샘플) |
| 좌석 중복 예약 | 0건 | **0건** (100 동시 예약 → 201×1 + 409×99, DB 활성티켓 1) |

### 도달 과정에서 발견·수정한 병목/버그

1. **auth bcrypt strength 12** — 1,000 가입+로그인 = 2,000 해싱 ≈ 540 코어-초 → 5s 타임아웃 → CB 오픈 연쇄 503. 강도 프로퍼티화(하한 10) + 가입은 사전 시드로 분리.
2. **train 좌석 가용 조회 N×Redis 왕복** — `isFree`가 비트당 GETBIT 1회: 검색 1회당 최대 ~2,000 왕복, DB 커넥션 점유 → Hikari 풀(30) 고갈 → 30s 대기 → 503. `check_free_batch.lua`로 스케줄당 1왕복으로 배치 (search p95 4s→21ms).
3. **소비된 큐 토큰 재반환 (서버측 근본 수정)** — `QueueService.enter()`가 기존 active 버킷의 (이미 소비된) 토큰을 재반환 → 재예매 401. enter 시 신규 토큰 재발급으로 수정.

## 결과 확인

- HTML 리포트: `results/html-report/index.html`
- Grafana: `http://localhost:3000` / Zipkin: `http://localhost:9411`
- Overbooking 검증 SQL:
  ```sql
  SELECT COUNT(*) FROM xrail_train.tickets t1
  JOIN xrail_train.tickets t2 ON t1.schedule_id=t2.schedule_id AND t1.seat_id=t2.seat_id
    AND t1.ticket_id < t2.ticket_id
    AND t1.start_station_idx < t2.end_station_idx AND t2.start_station_idx < t1.end_station_idx
  JOIN xrail_train.reservations r1 ON r1.reservation_id=t1.reservation_id AND r1.status IN ('PENDING','PAID')
  JOIN xrail_train.reservations r2 ON r2.reservation_id=t2.reservation_id AND r2.status IN ('PENDING','PAID');
  ```
