package com.turnstile.backend.domain.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> transitionQueueScript;

    private static final String WAITING_QUEUE_KEY = "q:waiting";
    private static final String ACTIVE_QUEUE_KEY = "q:active";
    private static final String MAX_ACTIVE_CONFIG_KEY = "q:config:max_active"; // 동적 유입량 제어 키

    private static final long DEFAULT_MAX_ACTIVE_USERS = 500;   // 기본값
    private static final long ACTIVE_TTL_SECONDS = 300;         // 5분 뒤 만료

    @Scheduled(fixedDelay = 10000) // 10초마다 실행
    public void scheduleQueue() {
        long now = Instant.now().getEpochSecond();

        // 진입 완료 상태에서 현재 시간보다 만료시간이 작은 유저들 일괄 삭제
        redisTemplate.opsForZSet().removeRangeByScore(ACTIVE_QUEUE_KEY, 0, now);

        // 동적 허용 인원 설정값 가져오기
        String maxActiveStr = redisTemplate.opsForValue().get(MAX_ACTIVE_CONFIG_KEY);
        long maxActiveUsers = (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : DEFAULT_MAX_ACTIVE_USERS;

        // 현재 진입 완료 상태 확인
        Long currentActiveCount = redisTemplate.opsForZSet().size(ACTIVE_QUEUE_KEY);
        if (currentActiveCount == null) currentActiveCount = 0L;

        // 빈자리 수 계산
        long availableSlots = maxActiveUsers - currentActiveCount;

        // 빈자리가 있다면 대기열 -> 진입 완료 상태로 전환
        if (availableSlots > 0) {
            long expireTimestamp = now + ACTIVE_TTL_SECONDS;

            List<String> enteredUsers = redisTemplate.execute(
                    transitionQueueScript,                          // Lua 스크립트 객체
                    List.of(WAITING_QUEUE_KEY, ACTIVE_QUEUE_KEY),   // KEYS
                    String.valueOf(availableSlots),                 // ARGV[1]: 진입 완료 상태로 넘길 빈자리 수
                    String.valueOf(expireTimestamp)                 // ARGV[2]: 만료 시간
            );

            if (enteredUsers != null && !enteredUsers.isEmpty()) {
                log.info("대기열 -> 진입 완료 상태 전환 완료: {}명 입장 (현재 설정된 최대 진입 완료 인원: {})", enteredUsers.size(), maxActiveUsers);
            }
        }
    }
}