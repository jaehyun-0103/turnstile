-- KEYS[1]: queue:concert:{concertId}:waiting
-- KEYS[2]: queue:concert:{concertId}:active
-- KEYS[3]: queue:scheduler:active_concerts
-- ARGV[1]: 전체 허용 정원
-- ARGV[2]: 만료 시간 (Unix Timestamp)
-- ARGV[3]: 초당 유입 제한량 (Batch Size / N)
-- ARGV[4]: 콘서트 ID
-- Score: 만료시간

local waitingKey = KEYS[1]
local activeKey = KEYS[2]
local activeConcertsKey = KEYS[3]
local maxCapacity = tonumber(ARGV[1])
local expireScore = tonumber(ARGV[2])
local batchSize = tonumber(ARGV[3])
local concertId = ARGV[4]

-- ZCARD로 현재 활성 유저 확인
local currentActiveCount = redis.call('ZCARD', activeKey)
local availableSlots = maxCapacity - currentActiveCount
local enteredUsers = {}

if availableSlots > 0 then
    -- 빈자리(N)와 설정된 TPS 한계치 중 작은 값 계산 (Throttling)
    local admissionCount = math.min(availableSlots, batchSize)

    if admissionCount > 0 then
        -- ZPOPMIN을 사용하여 원자적 추출 및 삭제
        local poppedElements = redis.call('ZPOPMIN', waitingKey, admissionCount)

        -- ZPOPMIN의 반환값은 [member1, score1, member2, score2...] 형태의 배열
        if #poppedElements > 0 then
            -- 2개씩 건너뛰며 userId만 추출하여 ZADD 수행
            for i = 1, #poppedElements, 2 do
                local userId = poppedElements[i]
                redis.call('ZADD', activeKey, expireScore, userId)
                table.insert(enteredUsers, userId)
            end
        end
    end
end

-- 스케줄러의 무의미한 순회를 막기 위해 active_concerts 목록에서 삭제
if redis.call('ZCARD', waitingKey) == 0 and redis.call('ZCARD', activeKey) == 0 then
    redis.call('SREM', activeConcertsKey, concertId)
end

return enteredUsers