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
    private final DefaultRedisScript<List> transitionQueueScript;

    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";
    private static final long DEFAULT_MAX_ACTIVE_USERS = 500; // 기본값
    private static final long ACTIVE_TTL_SECONDS = 300; // 5분 뒤 만료

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

        // 진입 완료 상태에서 만료된 유저 일괄 삭제
        redisTemplate.opsForZSet().removeRangeByScore(activeKey, 0, now);

        // 콘서트별 동적 허용 인원 설정값 가져오기
        String maxActiveStr = redisTemplate.opsForValue().get(maxActiveConfigKey);
        long maxActiveUsers = (maxActiveStr != null) ? Long.parseLong(maxActiveStr) : DEFAULT_MAX_ACTIVE_USERS;

        // 현재 진입 완료 상태 확인
        Long currentActiveCount = redisTemplate.opsForZSet().size(activeKey);
        if (currentActiveCount == null) currentActiveCount = 0L;

        // 빈자리 수 계산
        long availableSlots = maxActiveUsers - currentActiveCount;

        // 빈자리가 있다면 대기열 -> 진입 완료 상태로 전환
        if (availableSlots > 0) {
            long expireTimestamp = now + ACTIVE_TTL_SECONDS;

            List<String> enteredUsers = redisTemplate.execute(
                    transitionQueueScript,
                    List.of(waitingKey, activeKey),
                    String.valueOf(availableSlots),
                    String.valueOf(expireTimestamp)
            );

            if (enteredUsers != null && !enteredUsers.isEmpty()) {
                log.info("콘서트 {} 대기열 -> 진입 완료 상태 전환 완료: {}명 입장", concertIdStr, enteredUsers.size());
            }
        }
    }
}