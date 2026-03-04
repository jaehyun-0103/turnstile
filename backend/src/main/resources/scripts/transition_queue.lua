-- KEYS[1]: q:waiting:{concertId}
-- KEYS[2]: q:active:{concertId}
-- ARGV[1]: 전체 허용 정원
-- ARGV[2]: 만료 시간 (Unix Timestamp)
-- Score: 만료시간

local waitingKey = KEYS[1]
local activeKey = KEYS[2]
local maxCapacity = tonumber(ARGV[1])
local expireScore = tonumber(ARGV[2])

-- Lua 내부에서 진입 완료 상태 인원 확인
local currentActiveCount = redis.call('ZCARD', activeKey)
local availableSlots = maxCapacity - currentActiveCount

if availableSlots > 0 then
    local users = redis.call('ZRANGE', waitingKey, 0, availableSlots - 1)

    if #users > 0 then
        -- 조회된 N명을 대기열에서 삭제
        redis.call('ZREMRANGEBYRANK', waitingKey, 0, availableSlots - 1)

        -- 진입 완료 상태에 유저를 추가할 때, 만료 시간을 Score 값으로 설정하여 등록
        for i, user in ipairs(users) do
            redis.call('ZADD', activeKey, expireScore, user)
        end
        return users
    end
end

return {}