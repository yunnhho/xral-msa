-- promote.lua
-- §4.1 Capacity-aware 승급: "top N 무조건"이 아니라 "빈 자리만큼만" 원자적으로 승급한다.
-- KEYS[1] = queue:waiting:{scope}
-- KEYS[2] = queue:active:set:{scope}
-- ARGV[1] = maxActive
-- ARGV[2] = batchSize
-- ARGV[3] = now (epoch ms) — 만료분 정리 기준. Java가 승급된 uid의 토큰 issuedAt으로도 그대로 재사용한다.
-- ARGV[4] = expiresAt (epoch ms) = now + activeTtlSeconds*1000 — active-set score
-- Returns: 승급된 userId(문자열) 목록 (waiting 순서 유지)

local waitingKey = KEYS[1]
local activeKey  = KEYS[2]
local maxActive  = tonumber(ARGV[1])
local batchSize  = tonumber(ARGV[2])
local now        = tonumber(ARGV[3])
local expiresAt  = tonumber(ARGV[4])

-- 만료된 active 원소 정리 후 카운트 (즉시입장 Lua와 동일하게 정리 후 계산해야 자리를 오판하지 않는다)
redis.call('ZREMRANGEBYSCORE', activeKey, 0, now)
local activeCount = redis.call('ZCARD', activeKey)

local capacity = maxActive - activeCount
if capacity < 0 then
    capacity = 0
end
local n = batchSize
if capacity < n then
    n = capacity
end
if n <= 0 then
    return {}
end

local top = redis.call('ZRANGE', waitingKey, 0, n - 1)
if #top == 0 then
    return {}
end

for _, uid in ipairs(top) do
    redis.call('ZADD', activeKey, expiresAt, uid)
    redis.call('ZREM', waitingKey, uid)
end

return top
