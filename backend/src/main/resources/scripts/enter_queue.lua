-- KEYS[1]: q:active:{concertId}
-- KEYS[2]: q:waiting:{concertId}
-- KEYS[3]: q:counter:{concertId}
-- KEYS[4]: q:concerts:active
-- ARGV[1]: User ID
-- ARGV[2]: Concert ID

local activeKey = KEYS[1]
local waitingKey = KEYS[2]
local counterKey = KEYS[3]
local activeConcertsKey = KEYS[4]
local userId = ARGV[1]
local concertId = ARGV[2]

-- 활성 유저 확인: 이미 대기열을 통과해 입장한 유저인지 확인
if redis.call('ZSCORE', activeKey, userId) then
    return -1 -- 약속된 코드: -1을 반환하면 Java에서 "ACTIVE"로 처리
end

-- 기 진입 여부 확인: 이미 대기열에서 기다리고 있는 유저인지 확인
local existingScore = redis.call('ZSCORE', waitingKey, userId)
if existingScore then
    return tonumber(existingScore) -- 이미 있으면 기존 순번 그대로 반환
end

-- 신규 진입 처리 및 순번 할당
local newScore = redis.call('INCR', counterKey)
redis.call('ZADD', waitingQueueKey, newScore, userId)

-- 스케줄러가 이 콘서트 대기열을 순회하도록 트리거(SADD) 활성화
redis.call('SADD', activeConcertsKey, concertId)

-- 발급받은 순번 반환
return tonumber(newScore)