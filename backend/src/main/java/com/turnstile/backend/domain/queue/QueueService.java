package com.turnstile.backend.domain.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> enterQueueScript;

    private static final String QUEUE_COUNTER_KEY = "q:counter";
    private static final String WAITING_QUEUE_KEY = "q:waiting";

    // 대기열 진입
    public Long enterQueue(Long userId) {
        return redisTemplate.execute(
                enterQueueScript,                               // Lua 스크립트 객체
                List.of(QUEUE_COUNTER_KEY, WAITING_QUEUE_KEY),  // KEYS (이름표)
                String.valueOf(userId)                          // ARGV (실제 데이터 값)
        );
    }
}