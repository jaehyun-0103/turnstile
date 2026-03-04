package com.turnstile.backend.domain.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List<String>> transitionQueueScript;

    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    @Scheduled(fixedDelay = 10000) // 10초마다 실행
    public void scheduleQueue() {
        // 활성화된 콘서트 목록 조회
        Set<String> activeConcerts = redisTemplate.opsForSet().members(ACTIVE_CONCERTS_KEY);
        if (activeConcerts == null || activeConcerts.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();

        // 각 콘서트별로 대기열 상태 갱신
        for (String concertIdStr : activeConcerts) {
            processConcertQueue(concertIdStr, now);
        }
    }

    private void processConcertQueue(String concertIdStr, long now) {
        String waitingKey = "q:waiting:" + concertIdStr;
        String activeKey = "q:active:" + concertIdStr;
        String maxActiveConfigKey = "q:config:max_active:" + concertIdStr; // 동적 유입량 제어 키

        // 만료 데이터 정리
        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, now);

        // 동적 허용 인원 동적 설정값 조회 (기본값: 100)
        String maxActiveStr = redisTemplate.opsForValue().get(maxActiveConfigKey);
        long maxCapacity = (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : 100L;

        List<String> enteredUsers = redisTemplate.execute(
                transitionQueueScript,
                List.of(waitingKey, activeKey),
                String.valueOf(maxCapacity),
                String.valueOf(now + 300)
        );

        if (!enteredUsers.isEmpty()) {
            log.info("콘서트 {} 입장 처리 완료: {}명", concertIdStr, enteredUsers.size());
        }
    }
}