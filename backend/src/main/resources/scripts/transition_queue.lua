-- KEYS[1]: q:waiting
-- KEYS[2]: q:active
-- ARGV[1]: 빈자리 수
-- ARGV[2]: 만료 시간
-- Score: 만료시간

local waitingKey = KEYS[1]
local activeKey = KEYS[2]
local moveCount = tonumber(ARGV[1])
local expireScore = tonumber(ARGV[2])

-- 대기열에서 가장 순번이 빠른 N명 조회
local users = redis.call('ZRANGE', waitingKey, 0, moveCount - 1)

if #users > 0 then
    -- 조회된 N명을 대기열에서 삭제
    redis.call('ZREMRANGEBYRANK', waitingKey, 0, moveCount - 1)

    -- 진입 완료 상태에 유저를 추가할 때, 만료 시간을 Score 값으로 설정하여 등록
    for i, user in ipairs(users) do
        redis.call('ZADD', activeKey, expireScore, user)
    end
end

-- 대기열에서 삭제된 유저 목록 반환
return users