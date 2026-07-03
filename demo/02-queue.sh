#!/usr/bin/env bash
# demo/02-queue.sh — 머니샷2 · 대기열 SSE 실시간 순번 감소.
#
# QUEUE_USERS(기본 1,000)명을 대기열에 등록 → 데모 유저 1명이 EventSource(SSE)로
# 3초마다 100명씩 승급되며 순번이 줄어드는 것을 실시간 확인. AUTO/FORCE_ON 토글도 시연.
#
#   ./demo/02-queue.sh
#   QUEUE_USERS=300 ./demo/02-queue.sh   # 더 빠른 촬영용
#
# 승급 로직: queue-service QueueScheduler(fixedDelay=3s) 가 waiting top-100 → active 승급 후
# 대기 중 SSE emitter 전원에게 rank 이벤트를 push (신규 코드 아님, 기존 동작 트리거).

set -euo pipefail
cd "$(dirname "$0")/.."
source "demo/lib.sh"

need docker; need curl; need python3

QUEUE_USERS="${QUEUE_USERS:-1000}"
SCOPE="queue-demo"
DEMO_UID="${DEMO_UID:-777}"
BG_BASE=1000000                       # 배경 대기자 userId 오프셋(데모 유저와 충돌 방지)
MAX_WATCH="${MAX_WATCH:-120}"         # SSE 관찰 최대 시간(초)
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; kill "${CURL_PID:-0}" 2>/dev/null || true' EXIT

# ── 리셋 + 운영자 토글 시연 ────────────────────────────────────────
step "대기열 초기화 & 운영자 모드 토글 시연"
redis_cli "$REDIS_DB_QUEUE" FLUSHDB >/dev/null
log "현재 모드 → $(get_queue_mode | json_get data mode) (기본 AUTO)"
log "운영자 API로 강제 대기 전환: PUT /api/admin/queue/mode {FORCE_ON}"
set_queue_mode FORCE_ON >/dev/null
ok "모드 = $(get_queue_mode | json_get data mode) — 모든 진입이 대기열로"

# ── 배경 대기자 등록 ───────────────────────────────────────────────
step "대기열에 ${QUEUE_USERS}명 등록 (병렬)"
enter_one() { curl -s -o /dev/null -X POST "$QUEUE/api/queue/token" \
  -H "Content-Type: application/json" -H "X-User-Id: $1" -d "{\"scope\":\"$SCOPE\"}"; }
for i in $(seq 1 "$QUEUE_USERS"); do
  enter_one $((BG_BASE + i)) &
  [ $((i % 50)) -eq 0 ] && wait
done
wait
ok "등록 완료 (대기열에 ${QUEUE_USERS}명 투입)"

# ── 데모 유저 진입 (맨 뒤 순번) ────────────────────────────────────
step "데모 유저(#$DEMO_UID) 대기열 진입"
enter_resp=$(curl -s -X POST "$QUEUE/api/queue/token" \
  -H "Content-Type: application/json" -H "X-User-Id: $DEMO_UID" -d "{\"scope\":\"$SCOPE\"}")
START_RANK=$(printf '%s' "$enter_resp" | json_get data rank)
ok "진입 순번: ${START_RANK:-?}번"

# ── SSE 구독 → 실시간 순번 감소 관찰 ──────────────────────────────
step "SSE 구독 (GET /api/queue/subscribe) · 3초마다 100명 승급"
FIFO="$TMP/sse"; mkfifo "$FIFO"
curl -sN --max-time "$MAX_WATCH" "$QUEUE/api/queue/subscribe?scope=$SCOPE" \
  -H "X-User-Id: $DEMO_UID" > "$FIFO" &
CURL_PID=$!

ev=""
while IFS= read -r line; do
  line="${line%$'\r'}"                 # SSE 라인 끝 CR 제거
  case "$line" in
    event:*) ev="${line#event:}"; ev="${ev# }" ;;   # "event:rank" / "event: rank" 모두 처리
    data:*)
      d="${line#data:}"; d="${d# }"
      if [ "$ev" = "rank" ]; then
        r=$(printf '%s' "$d" | json_get rank)
        tot=$(printf '%s' "$d" | json_get totalWaiting)
        eta=$(printf '%s' "$d" | json_get expectedWaitSeconds)
        printf "\r  %s⏳ 내 순번 %-5s%s / 대기 %-5s · 예상 %ss   " "$C_YELLOW" "$r" "$C_RESET" "$tot" "$eta"
      elif [ "$ev" = "active" ]; then
        printf "\n"; ok "🎉 입장! ACTIVE — 큐토큰 발급, 예매 화면으로 진입"; break
      fi ;;
  esac
done < "$FIFO"
# kill 후 wait까지 stderr를 묶어야 bash의 "Terminated" 잡 메시지가 출력에 섞이지 않는다
{ kill "$CURL_PID" && wait "$CURL_PID"; } 2>/dev/null || true

# ── 마무리: 평시 모드로 복귀 시연 ─────────────────────────────────
step "운영자 모드 복귀 (FORCE_ON → AUTO)"
set_queue_mode AUTO >/dev/null
ok "모드 = $(get_queue_mode | json_get data mode) — 평시엔 대기열 우회(임계치 초과 시 자동 대기)"
redis_cli "$REDIS_DB_QUEUE" FLUSHDB >/dev/null
printf "\n%s✅ 머니샷2 완료%s — 1,000명 대기 → SSE로 순번 실시간 감소 → 입장\n\n" "$C_BOLD$C_GREEN" "$C_RESET"
