package com.turnstile.backend.domain.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> enterQueueScript;
    private final DefaultRedisScript<List<String>> transitionQueueScript;

    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    // Lua 스크립트를 통한 원자적 대기열 진입 및 트리거 활성화
    public Long enterQueue(String concertIdStr, String userIdStr) {
        String activeKey = "q:active:" + concertIdStr;
        String waitingKey = "q:waiting:" + concertIdStr;
        String counterKey = "q:counter:" + concertIdStr;

        // KEYS[4], ARGV[2] 매핑
        return redisTemplate.execute(
                enterQueueScript,
                List.of(activeKey, waitingKey, counterKey, ACTIVE_CONCERTS_KEY),
                userIdStr,
                concertIdStr
        );
    }

    // Lua 스크립트를 통한 원자적 대기열 상태 전이 (입장)
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

    // 현재 대기 순위(Rank) 조회
    public Long getWaitingRank(String concertIdStr, String userIdStr) {
        String waitingKey = "q:waiting:" + concertIdStr;
        return redisTemplate.opsForZSet().rank(waitingKey, userIdStr);
    }

    // 활성(ACTIVE) 상태 여부 조회
    public boolean isActiveUser(String concertIdStr, String userIdStr) {
        String activeKey = "q:active:" + concertIdStr;
        return redisTemplate.opsForZSet().score(activeKey, userIdStr) != null;
    }

    // 활성화된 콘서트(스케줄러 순회 대상) 목록 조회
    public Set<String> getActiveConcerts() {
        return redisTemplate.opsForSet().members(ACTIVE_CONCERTS_KEY);
    }

    // 만료된 활성 유저 데이터 정리
    public void removeExpiredActiveUsers(String concertIdStr, long now) {
        String activeKey = "q:active:" + concertIdStr;
        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, now);
    }

    // 동적 허용 인원 설정값 조회 (기본값: 100)
    public long getMaxActiveConfig(String concertIdStr) {
        String maxActiveConfigKey = "q:config:max_active:" + concertIdStr;
        String maxActiveStr = redisTemplate.opsForValue().get(maxActiveConfigKey);

        // 초당 100명이 진입하여 5분(300초) 머물면 최대 수용 공간은 30,000명 필요
        return (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : 5000L;
    }
}