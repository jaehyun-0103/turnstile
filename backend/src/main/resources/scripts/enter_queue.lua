-- KEYS[1]: q:counter:{concertId}
-- KEYS[2]: q:waiting:{concertId}
-- ARGV[1]: User ID
-- Score: 순번

local counterKey = KEYS[1]
local waitingQueueKey = KEYS[2]
local userId = ARGV[1]

-- 유저가 이미 대기열에 있는지 확인 (멱등성 검증)
local existingScore = redis.call('ZSCORE', waitingQueueKey, userId)
if existingScore then
    return tonumber(existingScore) -- 이미 있으면 기존 순번 그대로 반환
end

-- 새로운 순번 할당 (INCR)
local newScore = redis.call('INCR', counterKey)

-- 대기열에 유저를 추가할 때, 발급받은 순번을 Score 값으로 설정하여 등록
redis.call('ZADD', waitingQueueKey, newScore, userId)

-- 발급받은 순번 반환
return tonumber(newScore)