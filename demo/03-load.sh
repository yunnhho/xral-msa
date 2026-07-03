#!/usr/bin/env bash
# demo/03-load.sh — 머니샷3 · 부하 테스트 회복(에러율 0%, 예약 p95, HikariCP 안정).
#
# 기존 JMeter 플랜(docs/jmeter/xrail-load-test.jmx)을 스모크 → 본테스트 순으로 실행하고,
# Grafana/Zipkin 확인 위치를 안내한다. (신규 파일 없음 — 기존 자산 트리거)
#
#   ./demo/03-load.sh              # 스모크(20) → 본테스트(1,000)
#   USERS=500 ./demo/03-load.sh
#   SMOKE_ONLY=1 ./demo/03-load.sh # 스모크만
#
# 사전: ./demo-up.sh (rate-limit off/mock-fail 0 오버라이드 포함) + ./demo/seed.sh (계정 1,000).

set -euo pipefail
cd "$(dirname "$0")/.."
source "demo/lib.sh"

USERS="${USERS:-1000}"
RAMPUP="${RAMPUP:-60}"
JMX="docs/jmeter/xrail-load-test.jmx"
RESULTS="docs/jmeter/results"

command -v jmeter >/dev/null 2>&1 || die "jmeter 가 필요합니다. (macOS: brew install jmeter) · 플랜: $JMX"
[ -f "$JMX" ] || die "$JMX 를 찾을 수 없습니다."
if [ ! -s docs/jmeter/users.csv ]; then
  warn "users.csv 없음 → ./demo/seed.sh 로 계정을 먼저 시드하세요."; exit 1
fi
mkdir -p "$RESULTS"

step "스모크 테스트 (20 users) — 라우팅/시나리오 검증"
jmeter -n -t "$JMX" -l "$RESULTS/smoke.csv" -JUSERS=20 -JRAMPUP=5 \
  -JHOST=localhost -JPORT=8080
ok "스모크 완료 ($RESULTS/smoke.csv)"

if [ "${SMOKE_ONLY:-0}" = "1" ]; then ok "SMOKE_ONLY=1 → 본테스트 skip"; exit 0; fi

step "본 테스트 ($USERS 동접, ramp-up ${RAMPUP}s) — HTML 리포트 생성"
rm -rf "$RESULTS/html-demo"
HEAP="-Xms1g -Xmx2g" jmeter -n -t "$JMX" \
  -l "$RESULTS/demo-run.csv" -e -o "$RESULTS/html-demo" \
  -JUSERS="$USERS" -JRAMPUP="$RAMPUP" -JHOST=localhost -JPORT=8080
ok "본 테스트 완료"

cat <<EOF

${C_BOLD}${C_GREEN}✅ 부하 테스트 완료${C_RESET}
   JMeter HTML 리포트   $RESULTS/html-demo/index.html
   Grafana 대시보드     $GRAFANA/dashboards   (XRail · admin/admin, 에러율/예약 p95/HikariCP)
   Zipkin 분산 트레이스  $ZIPKIN              (6개 서비스 단일 trace 확인)

   Grafana에서 실시간 그래프(에러율 0% · 예약 p95 · Hikari active 안정)를 촬영하세요.
EOF
