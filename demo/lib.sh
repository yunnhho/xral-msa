#!/usr/bin/env bash
# demo/lib.sh — 데모 스크립트 공통 함수/상수.
# 각 데모 스크립트가 `source "$(dirname "$0")/lib.sh"` 로 로드한다.
#
# 설계 원칙:
#  - 새 엔드포인트를 만들지 않는다. 기존 서비스 포트를 직접 호출한다.
#  - 트리거 스크립트는 Gateway를 우회하고 서비스에 직접 요청하되,
#    Gateway가 주입했을 신뢰 헤더(X-User-Id / X-User-Role / X-User-Name)를 우리가 넣는다.
#    (P1 원칙: downstream은 Gateway 헤더를 신뢰. rate-limit/LB 예열 이슈 없이 재현성 확보)
#  - 좌석 동시성 안전망(HMAC 큐토큰 → Lua 비트마스크 → DB 더블체크)은 그대로 통과한다.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$DEMO_DIR/.." && pwd)"

# ── 서비스 주소 (환경변수로 override 가능) ─────────────────────────
GATEWAY="${GATEWAY:-http://localhost:8080}"
AUTH="${AUTH:-http://localhost:8081}"
TRAIN="${TRAIN:-http://localhost:8082}"
QUEUE="${QUEUE:-http://localhost:8084}"
PAYMENT="${PAYMENT:-http://localhost:8085}"
NOTIFY="${NOTIFY:-http://localhost:8086}"
DISCOVERY="${DISCOVERY:-http://localhost:8761}"
PROMETHEUS="${PROMETHEUS:-http://localhost:9090}"
GRAFANA="${GRAFANA:-http://localhost:3000}"
ZIPKIN="${ZIPKIN:-http://localhost:9411}"

# ── Redis logical DB 인덱스 (P5) ───────────────────────────────────
REDIS_DB_TRAIN=1
REDIS_DB_QUEUE=2
REDIS_CONTAINER="${REDIS_CONTAINER:-xrail-redis}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-xrail-mysql}"

# ── 색상 로깅 ──────────────────────────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_BOLD=$'\033[1m'
else
  C_RESET=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_BOLD=""
fi

step() { printf "\n%s▶ %s%s\n" "$C_BOLD$C_BLUE" "$*" "$C_RESET"; }
log()  { printf "  %s\n" "$*"; }
ok()   { printf "  %s✓%s %s\n" "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf "  %s!%s %s\n" "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf "  %s✗%s %s\n" "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' 명령이 필요합니다 (PATH 확인)"; }

# ── .env 로드 (MYSQL_ROOT_PASSWORD 등) ─────────────────────────────
load_env() {
  if [ -f "$ROOT_DIR/.env" ]; then
    set -a; # shellcheck disable=SC1091
    . "$ROOT_DIR/.env"; set +a
  fi
}

# ── JSON 파서 (jq 의존성 제거, python3 사용) ───────────────────────
# 사용: echo "$json" | json_get data schedules 0 scheduleId
json_get() {
  # 주의: JSON은 stdin(파이프)으로, 키 경로는 argv로 전달한다.
  # (heredoc을 stdin으로 쓰면 파이프 JSON을 덮어써 버리므로 -c 방식을 쓴다.)
  python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    print(""); sys.exit(0)
for k in sys.argv[1:]:
    if isinstance(data, list):
        data = data[int(k)] if k.lstrip("-").isdigit() and int(k) < len(data) else None
    elif isinstance(data, dict):
        data = data.get(k)
    else:
        data = None
    if data is None:
        break
print("" if data is None else data)
' "$@"
}

# ── ms 단위 timestamp / CAPTCHA 스텁 토큰 (Gateway 경유 시 필요) ────
now_ms()        { python3 -c 'import time; print(int(time.time()*1000))'; }
captcha_token() { python3 -c 'import time,base64; print(base64.b64encode(str(int(time.time()*1000)).encode()).decode())'; }

# ── 내일 날짜 (macOS/Linux 호환) ───────────────────────────────────
tomorrow() {
  if date -v+1d +%F >/dev/null 2>&1; then date -v+1d +%F; else date -d tomorrow +%F; fi
}

# ── infra helpers ──────────────────────────────────────────────────
# -D 필수: 다중 테이블 DELETE(alias)는 테이블을 db.table로 수식해도 기본 DB 선택을 요구한다(ERROR 1046).
# MYSQL_PWD: -p"..." 사용 시 매 호출 "insecure" 경고가 출력을 오염시키므로 env로 전달.
mysql_q()  { docker exec -i -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "$MYSQL_CONTAINER" mysql -uroot -N -B -D xrail_train -e "$1"; }
redis_cli() { local db="$1"; shift; docker exec -i "$REDIS_CONTAINER" redis-cli -n "$db" "$@"; }

# ── 큐 진입(서비스 직접 호출) → ACTIVE면 큐토큰 출력, 아니면 빈 문자열 ─
# 사용: token=$(queue_token <userId> <scope>)
queue_token() {
  local uid="$1" scope="$2" resp status
  resp=$(curl -s -X POST "$QUEUE/api/queue/token" \
    -H "Content-Type: application/json" -H "X-User-Id: $uid" \
    -d "{\"scope\":\"$scope\"}")
  status=$(printf '%s' "$resp" | json_get data status)
  [ "$status" = "ACTIVE" ] && printf '%s' "$resp" | json_get data queueToken
}

# ── 큐 모드 제어(운영자 API 직접 호출, ADMIN 헤더 주입) ────────────
set_queue_mode() {
  curl -s -o /dev/null -X PUT "$QUEUE/api/admin/queue/mode" \
    -H "Content-Type: application/json" -H "X-User-Role: ROLE_ADMIN" \
    -d "{\"mode\":\"$1\"}"
}
get_queue_mode() {
  curl -s "$QUEUE/api/admin/queue/mode" -H "X-User-Role: ROLE_ADMIN"
}

# ── 스케줄 검색 → 첫 scheduleId (경부선 서울(1)→부산(4), 내일) ──────
discover_schedule_id() {
  local dep="${1:-1}" arr="${2:-4}" date="${3:-$(tomorrow)}"
  curl -s "$TRAIN/api/schedules?departureStationId=$dep&arrivalStationId=$arr&date=$date" \
    | json_get data schedules 0 scheduleId
}
