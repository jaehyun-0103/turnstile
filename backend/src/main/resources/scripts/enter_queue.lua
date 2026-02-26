-- KEYS[1]: q:counter
-- KEYS[2]: q:waiting
-- ARGV[1]: User ID

local counterKey = KEYS[1]
local waitingQueueKey = KEYS[2]
local userId = ARGV[1]

-- 1. 유저가 이미 대기열에 있는지 확인 (멱등성 검증)
local existingScore = redis.call('ZSCORE', waitingQueueKey, userId)
if existingScore then
    return tonumber(existingScore) -- 이미 있으면 기존 순번(Score) 그대로 반환
end

-- 2. 새로운 순번(Score) 할당 (INCR)
local newScore = redis.call('INCR', counterKey)

-- 3. ZSET에 유저 등록
redis.call('ZADD', waitingQueueKey, newScore, userId)

-- 4. 발급받은 순번(Score) 반환
return tonumber(newScore)