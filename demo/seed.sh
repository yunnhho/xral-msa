#!/usr/bin/env bash
# demo/seed.sh — 데모 상태를 매번 동일하게 리셋(멱등) + 부하용 계정 시드.
#
#   ./demo/seed.sh              # 리셋 + 계정 1,000개 시드(JMeter용)
#   SEED_USERS=0 ./demo/seed.sh # 리셋만 (계정 시드 skip, 빠름)
#
# 리셋 대상:
#  - 큐 Redis(DB2) FLUSHDB  → 모드 AUTO, 대기/active 초기화
#  - train Redis(DB1) 데모 좌석 비트마스크 DEL
#  - 데모 예약(user_id 1..200) 및 티켓 삭제
# 스케줄/좌석 기준 데이터는 train-service 부팅 시 Flyway + 자동 백필로 이미 존재(오늘~+30일).

set -euo pipefail
cd "$(dirname "$0")/.."
source "demo/lib.sh"

need docker; need curl; need python3
load_env
: "${MYSQL_ROOT_PASSWORD:?.env 에 MYSQL_ROOT_PASSWORD 가 필요합니다}"

SEED_USERS="${SEED_USERS:-1000}"
DEMO_SEAT="${DEMO_SEAT:-1}"           # 오버셀 데모 대상 좌석
DEMO_USER_RANGE_MAX=200               # 데모 트리거가 쓰는 X-User-Id 상한

step "1 · 스케줄 대상 확인 (경부선 서울1→부산4, $(tomorrow))"
SCHED=$(discover_schedule_id 1 4 "$(tomorrow)")
[ -n "$SCHED" ] || die "스케줄을 찾지 못했습니다. train-service 부팅/백필을 확인하세요 ($TRAIN)."
ok "대상 scheduleId=$SCHED · seatId=$DEMO_SEAT"

step "2 · 큐 상태 리셋 (Redis DB$REDIS_DB_QUEUE FLUSHDB → 모드 AUTO)"
redis_cli "$REDIS_DB_QUEUE" FLUSHDB >/dev/null
ok "대기열 초기화 완료"

step "3 · 데모 좌석 락 리셋 (Redis DB$REDIS_DB_TRAIN)"
redis_cli "$REDIS_DB_TRAIN" DEL "sch:${SCHED}:seat:${DEMO_SEAT}" >/dev/null
ok "sch:${SCHED}:seat:${DEMO_SEAT} 비트마스크 해제"

step "4 · 데모 예약/티켓 삭제 (user_id 1..$DEMO_USER_RANGE_MAX)"
mysql_q "DELETE t FROM xrail_train.tickets t
         JOIN xrail_train.reservations r ON t.reservation_id = r.reservation_id
         WHERE r.user_id BETWEEN 1 AND $DEMO_USER_RANGE_MAX;
         DELETE FROM xrail_train.reservations WHERE user_id BETWEEN 1 AND $DEMO_USER_RANGE_MAX;"
ok "데모 예약 삭제 완료"

if [ "$SEED_USERS" -gt 0 ]; then
  step "5 · 부하용 계정 시드 ($SEED_USERS 개, 멱등) — JMeter 시나리오용"
  python3 docs/jmeter/seed-users.py "$SEED_USERS" "$AUTH"
  ok "users.csv 생성 (docs/jmeter/users.csv)"
else
  warn "SEED_USERS=0 → 계정 시드 skip"
fi

cat <<EOF

${C_BOLD}${C_GREEN}✅ 시드/리셋 완료${C_RESET}  (scheduleId=$SCHED, seatId=$DEMO_SEAT, date=$(tomorrow))
   이제 ./demo/01-oversell.sh 또는 ./demo/02-queue.sh 를 실행하세요.
EOF
