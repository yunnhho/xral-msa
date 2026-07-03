#!/usr/bin/env bash
# demo-up.sh — 한 줄로 전체 스택(인프라 + 관측 + 6서비스)을 띄우고 전부 green이 될 때까지 대기.
#
#   ./demo-up.sh              # 빌드 후 기동 + 헬스 대기
#   ./demo-up.sh --no-build   # 이미 빌드된 이미지로 기동
#   ./demo-up.sh --seed       # 기동 후 demo/seed.sh 까지 실행
#
# 앱 서비스에는 compose healthcheck가 없으므로 이 스크립트가 /actuator/health 를 직접 폴링한다.

set -euo pipefail
cd "$(dirname "$0")"
source "demo/lib.sh"

BUILD_FLAG="--build"; DO_SEED=0
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD_FLAG="" ;;
    --seed)     DO_SEED=1 ;;
    -h|--help)  grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "알 수 없는 옵션: $arg" ;;
  esac
done

need docker; need curl; need python3

# docker compose (v2) vs docker-compose (v1) 자동 감지
if docker compose version >/dev/null 2>&1; then COMPOSE="docker compose"; else COMPOSE="docker-compose"; fi
COMPOSE="$COMPOSE -f docker-compose.yml -f docker-compose.demo.yml"

step "1/3 · 스택 기동 ($COMPOSE up -d $BUILD_FLAG)"
# shellcheck disable=SC2086
$COMPOSE up -d $BUILD_FLAG
ok "컨테이너 기동 요청 완료"

# ── 서비스별 health 폴링 ──────────────────────────────────────────
# name|url  (앱은 actuator/health, 관측 도구는 자체 엔드포인트)
CHECKS=(
  "discovery|http://localhost:8761/actuator/health"
  "gateway|http://localhost:8080/actuator/health"
  "auth|http://localhost:8081/actuator/health"
  "train|http://localhost:8082/actuator/health"
  "queue|http://localhost:8084/actuator/health"
  "payment|http://localhost:8085/actuator/health"
  "notification|http://localhost:8086/actuator/health"
  "prometheus|http://localhost:9090/-/healthy"
  "grafana|http://localhost:3000/api/health"
  "zipkin|http://localhost:9411/health"
)

wait_for() { # name url  → 0 green / 1 timeout
  local name="$1" url="$2" deadline=$(( $(date +%s) + 180 )) body
  printf "  %-14s " "$name"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    body=$(curl -fs --max-time 3 "$url" 2>/dev/null || true)
    # 매칭: actuator("status":"UP") · Prometheus("...Healthy.") · Grafana("database":"ok") · 일반 ok
    if printf '%s' "$body" | grep -qiE 'UP|Healthy|"database"[[:space:]]*:[[:space:]]*"?ok|^ok'; then
      printf "%s✓ green%s\n" "$C_GREEN" "$C_RESET"; return 0
    fi
    printf "."; sleep 3
  done
  printf " %s✗ timeout%s\n" "$C_RED" "$C_RESET"
  err "$name 헬스체크 실패 → 로그 확인: $COMPOSE logs $name"
  return 1
}

step "2/3 · 헬스체크 폴링 (서비스당 최대 180s)"
FAILED=0
for c in "${CHECKS[@]}"; do
  wait_for "${c%%|*}" "${c##*|}" || FAILED=1
done
[ "$FAILED" -eq 0 ] || die "일부 서비스가 green이 아닙니다. 위 로그 명령으로 원인을 확인하세요."

# ── Eureka LB 예열: Gateway가 라우팅을 잡을 때까지(스케줄 검색 200) ──
step "3/3 · Gateway 라우팅 예열 (최대 60s)"
deadline=$(( $(date +%s) + 60 ))
until curl -fs --max-time 3 "$TRAIN/api/schedules?departureStationId=1&arrivalStationId=4&date=$(tomorrow)" >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "$deadline" ] || die "train-service 스케줄 API가 응답하지 않습니다."
  printf "."; sleep 3
done
ok "스케줄 API 응답 확인 (라우팅 정상)"

cat <<EOF

${C_BOLD}${C_GREEN}✅ XRail 스택 준비 완료${C_RESET}

  Gateway      $GATEWAY
  Swagger(각)  :8081/8082/8084 .../swagger-ui.html
  Grafana      $GRAFANA        (admin/admin)
  Prometheus   $PROMETHEUS
  Zipkin       $ZIPKIN
  Eureka       $DISCOVERY

  다음 단계:
    ./demo/seed.sh          # 시드 + 상태 리셋(멱등)
    ./demo/01-oversell.sh   # 머니샷1 · 동시성 증명
    ./demo/02-queue.sh      # 머니샷2 · 대기열 SSE
    ./demo/03-load.sh       # 머니샷3 · 부하 테스트
EOF

if [ "$DO_SEED" -eq 1 ]; then step "추가 · demo/seed.sh 실행"; ./demo/seed.sh; fi
