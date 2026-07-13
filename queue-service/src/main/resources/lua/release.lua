-- release.lua
-- §4.2 슬롯 반환: 가드 판정(버킷 부재 skip, ABA/클록 skew 가드)과 삭제를 단일 트랜잭션으로 원자 실행한다.
-- 가드와 삭제를 분리하면 그 사이에 enter() 재발급이 끼어들어 방금 갱신된 새 세션을 지우는
-- TOCTOU가 생기므로 반드시 하나의 스크립트로 묶는다.
-- KEYS[1] = queue:active:{scope}:{userId}       (버킷, 값 = "hmac.issuedAt")
-- KEYS[2] = queue:active:set:{scope}             (active zset)
-- KEYS[3] = queue:active:first:{scope}:{userId}  (§4.6 세션 첫 issuedAt)
-- ARGV[1] = userId (active zset member)
-- ARGV[2] = occurredAt (epoch ms, train-service 클록 — reservation.created 발생 시각)
-- ARGV[3] = skewMarginMs
-- Returns: 1 = 반환됨, 0 = skip

local bucketKey    = KEYS[1]
local activeSetKey = KEYS[2]
local firstKey     = KEYS[3]
local uid          = ARGV[1]
local occurredAt   = tonumber(ARGV[2])
local skewMargin   = tonumber(ARGV[3])

-- 1) 버킷 부재 → skip. issuedAt을 판정할 근거가 없다(§4.2-1).
local tokenValue = redis.call('GET', bucketKey)
if not tokenValue then
    return 0
end

-- 토큰 형식 "hmac.issuedAt"에서 issuedAt(마지막 '.' 이후) 파싱
local lastDot = nil
for i = #tokenValue, 1, -1 do
    if string.sub(tokenValue, i, i) == '.' then
        lastDot = i
        break
    end
end
if not lastDot then
    return 0
end

local issuedAt = tonumber(string.sub(tokenValue, lastDot + 1))
if not issuedAt then
    return 0
end

-- 2) ABA 가드 + 클록 skew 마진(§4.2-2): issuedAt < occurredAt - skewMargin 일 때만 반환.
--    그 외(더 나중에 재발급된 세션이거나 skew로 판정 불가)면 skip — 오판은 안전측(skip)으로.
if not (issuedAt < occurredAt - skewMargin) then
    return 0
end

-- 3) 반환: 없는 원소 ZREM/DEL은 no-op이라 중복 반환에도 안전(INV-4)
redis.call('ZREM', activeSetKey, uid)
redis.call('DEL', bucketKey)
redis.call('DEL', firstKey)

return 1
