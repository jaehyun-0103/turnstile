package com.turnstile.backend.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Redis와 Lua 스크립트를 활용하여 대기열의 원자적 상태 변경 및 조회를 담당하는 리포지토리입니다.
 */
@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> enterQueueScript;
    private final DefaultRedisScript<List<String>> transitionQueueScript;

    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    /**
     * Lua 스크립트를 실행하여 사용자를 대기열에 원자적으로 진입시키고 스케줄러 트리거를 활성화합니다.
     * @return 대기 순번 (이미 활성 상태인 경우 -1 반환)
     */
    public Long enterQueue(String concertIdStr, String userIdStr) {
        String activeKey = "q:active:" + concertIdStr;
        String waitingKey = "q:waiting:" + concertIdStr;
        String counterKey = "q:counter:" + concertIdStr;

        return redisTemplate.execute(
                enterQueueScript,
                List.of(activeKey, waitingKey, counterKey, ACTIVE_CONCERTS_KEY),
                userIdStr,
                concertIdStr
        );
    }

    /**
     * 허용된 정원과 초당 유입 제한량(Batch Size)에 맞춰 대기 중인 유저를 활성(입장) 상태로 전이합니다.
     * @return 활성 상태로 전이된 사용자 ID 목록
     */
    public List<String> transitionQueue(String concertIdStr, long maxCapacity, long batchSize, long expireScore) {
        String waitingKey = "q:waiting:" + concertIdStr;
        String activeKey = "q:active:" + concertIdStr;

        return redisTemplate.execute(
                transitionQueueScript,
                List.of(waitingKey, activeKey),
                String.valueOf(maxCapacity),
                String.valueOf(expireScore),
                String.valueOf(batchSize)
        );
    }

    /**
     * 대기열에 있는 특정 사용자의 현재 순위를 조회합니다.
     */
    public Long getWaitingRank(String concertIdStr, String userIdStr) {
        String waitingKey = "q:waiting:" + concertIdStr;
        return redisTemplate.opsForZSet().rank(waitingKey, userIdStr);
    }

    /**
     * 특정 사용자가 현재 활성(입장 완료) 상태인지 확인합니다.
     */
    public boolean isActiveUser(String concertIdStr, String userIdStr) {
        String activeKey = "q:active:" + concertIdStr;
        return redisTemplate.opsForZSet().score(activeKey, userIdStr) != null;
    }

    /**
     * 대기열이 가동 중인 전체 콘서트 식별자 목록을 반환합니다.
     */
    public Set<String> getActiveConcerts() {
        return redisTemplate.opsForSet().members(ACTIVE_CONCERTS_KEY);
    }

    /**
     * 만료 시간(Score)이 지난 활성 상태 유저들을 일괄 삭제합니다.
     */
    public void removeExpiredActiveUsers(String concertIdStr, long now) {
        String activeKey = "q:active:" + concertIdStr;
        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, now);
    }

    /**
     * 동시 접속 최대 허용 인원(Max Capacity) 설정값을 조회합니다.
     * 설정값이 없을 경우 기본값인 5000명을 반환합니다.
     */
    public long getMaxActiveConfig(String concertIdStr) {
        String maxActiveConfigKey = "q:config:max_active:" + concertIdStr;
        String maxActiveStr = redisTemplate.opsForValue().get(maxActiveConfigKey);

        return (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : 5000L;
    }

    /**
     * 활성 상태 사용자의 만료 시간(Score)을 조회합니다.
     */
    public Double getActiveUserScore(String concertIdStr, String userIdStr) {
        String activeKey = "q:active:" + concertIdStr;
        return redisTemplate.opsForZSet().score(activeKey, userIdStr);
    }
}