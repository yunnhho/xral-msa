-- reserve_seat.lua
-- 좌석 segment 비트마스크 원자 예약
-- KEYS[1] = sch:{scheduleId}:seat:{seatId}
-- ARGV[1] = startIdx (inclusive)
-- ARGV[2] = endIdx   (exclusive)
-- Returns: 1 = 성공, 0 = 충돌

local key      = KEYS[1]
local startIdx = tonumber(ARGV[1])
local endIdx   = tonumber(ARGV[2])

-- 해당 구간 비트 중 이미 set된 게 있으면 충돌
for i = startIdx, endIdx - 1 do
    if redis.call('GETBIT', key, i) == 1 then
        return 0
    end
end

-- 모두 0이면 atomic하게 set
for i = startIdx, endIdx - 1 do
    redis.call('SETBIT', key, i, 1)
end

-- TTL 24h (키가 새로 생성될 때만 적용)
if redis.call('TTL', key) == -1 then
    redis.call('EXPIRE', key, 86400)
end

return 1
