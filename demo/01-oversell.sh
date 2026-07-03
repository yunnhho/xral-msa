#!/usr/bin/env bash
# demo/01-oversell.sh — 머니샷1 · 동시성 증명(오버부킹 0).
#
# 동일 좌석을 THREADS(기본 100)개 동시 예약 발사 → 정확히 1건만 201, 나머지 409.
# DB 활성 티켓 1건 / 오버부킹 0건을 SQL로 검증하고 표로 출력한다.
#
#   ./demo/01-oversell.sh            # 100 스레드
#   THREADS=50 ./demo/01-oversell.sh
#
# 안전망은 실제로 통과한다: HMAC 큐토큰 → Lua 비트마스크 원자락 → DB 비관적 락 더블체크.
# (Gateway는 우회하되, 예약 엔드포인트의 QueueTokenInterceptor/Lua/DB 3중 안전망은 그대로 동작)

set -euo pipefail
cd "$(dirname "$0")/.."
source "demo/lib.sh"

need docker; need curl; need python3
load_env
: "${MYSQL_ROOT_PASSWORD:?.env 에 MYSQL_ROOT_PASSWORD 가 필요합니다}"

THREADS="${THREADS:-100}"
SEAT="${DEMO_SEAT:-1}"
SCOPE="seat-demo"
RUN_ID="$(now_ms)"                     # Idempotency-Key 매 실행 유니크(캐시 replay 방지)
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; set_queue_mode AUTO >/dev/null 2>&1 || true' EXIT

# ── 대상 스케줄 확인 ───────────────────────────────────────────────
step "대상 스케줄 확인"
SCHED=$(discover_schedule_id 1 4 "$(tomorrow)")
[ -n "$SCHED" ] || die "스케줄을 찾지 못했습니다 — ./demo/seed.sh 를 먼저 실행하세요."
ok "scheduleId=$SCHED · seatId=$SEAT · 구간 서울(1)→부산(4) · $THREADS 스레드"

# ── 리셋: 좌석 락/예약 초기화 + FORCE_OFF(모두 즉시 큐토큰 발급) ────
step "상태 리셋 (좌석 락 해제 · 데모 예약 삭제 · 큐 FORCE_OFF)"
redis_cli "$REDIS_DB_TRAIN" DEL "sch:${SCHED}:seat:${SEAT}" >/dev/null
mysql_q "DELETE t FROM xrail_train.tickets t
         JOIN xrail_train.reservations r ON t.reservation_id = r.reservation_id
         WHERE t.schedule_id=$SCHED AND t.seat_id=$SEAT;
         DELETE FROM xrail_train.reservations r
         WHERE r.reservation_id NOT IN (SELECT reservation_id FROM xrail_train.tickets)
           AND r.user_id BETWEEN 1 AND $THREADS;"
set_queue_mode FORCE_OFF >/dev/null
ok "초기화 완료"

# ── Phase A: 큐토큰 선발급 (동시 발사 전에 미리 확보) ──────────────
step "Phase A · 큐토큰 ${THREADS}개 발급"
acquired=0
for i in $(seq 1 "$THREADS"); do
  ( t=$(queue_token "$i" "$SCOPE" || true); [ -n "$t" ] && printf '%s' "$t" > "$TMP/tok.$i" ) &
  # 동시 연결 폭주 방지: 50개 단위로 배압
  [ $((i % 50)) -eq 0 ] && wait
done
wait
for i in $(seq 1 "$THREADS"); do [ -s "$TMP/tok.$i" ] && acquired=$((acquired+1)); done
ok "큐토큰 확보: $acquired/$THREADS"
[ "$acquired" -ge 2 ] || die "큐토큰이 2개 미만입니다 — queue-service 상태를 확인하세요."

# ── Phase B: 동일 좌석 동시 예약 발사 ──────────────────────────────
step "Phase B · 동일 좌석 동시 예약 ${acquired}발 발사 🚀"
body="{\"scheduleId\":$SCHED,\"departureStationId\":1,\"arrivalStationId\":4,\"seatIds\":[$SEAT]}"
fire() {
  local i="$1" tok; tok=$(cat "$TMP/tok.$i")
  # scope 쿼리 파라미터 필수: QueueTokenInterceptor가 ?scope= 로 HMAC을 재계산(없으면 "global")
  curl -s -o /dev/null -w '%{http_code}' -X POST "$TRAIN/api/reservations?scope=$SCOPE" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: $i" -H "X-User-Name: demo-user-$i" \
    -H "X-Queue-Token: $tok" -H "Idempotency-Key: oversell-$RUN_ID-$i" \
    -d "$body" > "$TMP/code.$i"
}
for i in $(seq 1 "$THREADS"); do [ -s "$TMP/tok.$i" ] && fire "$i" & done
wait
ok "발사 완료"

# ── 집계 ───────────────────────────────────────────────────────────
c201=0; c409=0; cother=0
for i in $(seq 1 "$THREADS"); do
  [ -s "$TMP/code.$i" ] || continue
  case "$(cat "$TMP/code.$i")" in
    201) c201=$((c201+1)) ;;
    409) c409=$((c409+1)) ;;
    *)   cother=$((cother+1)) ;;
  esac
done

DB_TICKETS=$(mysql_q "SELECT COUNT(*) FROM xrail_train.tickets t
  JOIN xrail_train.reservations r ON t.reservation_id=r.reservation_id
  WHERE t.schedule_id=$SCHED AND t.seat_id=$SEAT AND r.status IN ('PENDING','PAID');")

OVERBOOK=$(mysql_q "SELECT COUNT(*) FROM xrail_train.tickets t1
  JOIN xrail_train.tickets t2 ON t1.schedule_id=t2.schedule_id AND t1.seat_id=t2.seat_id
    AND t1.ticket_id < t2.ticket_id
    AND t1.start_station_idx < t2.end_station_idx AND t2.start_station_idx < t1.end_station_idx
  JOIN xrail_train.reservations r1 ON r1.reservation_id=t1.reservation_id AND r1.status IN ('PENDING','PAID')
  JOIN xrail_train.reservations r2 ON r2.reservation_id=t2.reservation_id AND r2.status IN ('PENDING','PAID')
  WHERE t1.schedule_id=$SCHED AND t1.seat_id=$SEAT;")

# ── 결과 표 ────────────────────────────────────────────────────────
pass_ob="${C_GREEN}✅ PASS$C_RESET"; [ "$OVERBOOK" = "0" ] || pass_ob="${C_RED}❌ FAIL$C_RESET"
printf "\n%s┌─────────────────────────────────────────────┐%s\n" "$C_BOLD" "$C_RESET"
printf   "%s│   XRail · 동일 좌석 %3d 스레드 동시 예약 결과   │%s\n" "$C_BOLD" "$acquired" "$C_RESET"
printf   "%s└─────────────────────────────────────────────┘%s\n" "$C_BOLD" "$C_RESET"
printf "   %-26s %s%3d%s\n" "201 Created (성공)"       "$C_GREEN"  "$c201"    "$C_RESET"
printf "   %-26s %s%3d%s\n" "409 Conflict (좌석 선점)"  "$C_YELLOW" "$c409"    "$C_RESET"
printf "   %-26s %3d\n"     "기타 응답"                 "$cother"
printf "   %s─────────────────────────────────────%s\n" "$C_DIM" "$C_RESET"
printf "   %-26s %s%3d%s\n" "DB 활성 티켓"              "$C_BLUE"  "$DB_TICKETS" "$C_RESET"
printf "   %-26s %b (오버부킹 %s건)\n" "정합성"          "$pass_ob" "$OVERBOOK"
printf "\n"

[ "$c201" = "1" ] && [ "$DB_TICKETS" = "1" ] && [ "$OVERBOOK" = "0" ] \
  && ok "머니샷1 성립: 성공 1건 · DB 티켓 1건 · 오버부킹 0건" \
  || warn "기대치(201×1 / DB티켓 1 / 오버부킹 0)와 다릅니다 — 위 수치를 확인하세요."
