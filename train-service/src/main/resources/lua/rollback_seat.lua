-- rollback_seat.lua
-- 좌석 segment 비트마스크 해제 (보상 트랜잭션)
-- KEYS[1] = sch:{scheduleId}:seat:{seatId}
-- ARGV[1] = startIdx (inclusive)
-- ARGV[2] = endIdx   (exclusive)
-- Returns: 1 (항상)

local key      = KEYS[1]
local startIdx = tonumber(ARGV[1])
local endIdx   = tonumber(ARGV[2])

for i = startIdx, endIdx - 1 do
    redis.call('SETBIT', key, i, 0)
end

return 1
