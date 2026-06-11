-- check_free_batch.lua
-- 여러 좌석의 segment 가용 여부를 한 번의 호출로 조회 (읽기 전용)
-- KEYS[i] = sch:{scheduleId}:seat:{seatId}
-- ARGV[1] = startIdx (inclusive)
-- ARGV[2] = endIdx   (exclusive)
-- Returns: KEYS 순서대로 1(가용) / 0(점유) 배열

local startIdx = tonumber(ARGV[1])
local endIdx   = tonumber(ARGV[2])

local result = {}
for k, key in ipairs(KEYS) do
    local free = 1
    for i = startIdx, endIdx - 1 do
        if redis.call('GETBIT', key, i) == 1 then
            free = 0
            break
        end
    end
    result[k] = free
end

return result
