package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.JwtProvider;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
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

    private final JwtProvider jwtProvider;

    // 대기열 진입
    public Long enterQueue(Long userId) {
        return redisTemplate.execute(
                enterQueueScript,                               // Lua 스크립트 객체
                List.of(QUEUE_COUNTER_KEY, WAITING_QUEUE_KEY),  // KEYS (이름표)
                String.valueOf(userId)                          // ARGV (실제 데이터 값)
        );
    }

    // 폴링
    public QueueStatusResponse getQueueStatus(Long userId) {
        String userIdStr = String.valueOf(userId);

        // 진입 완료 상태인지 확인
        Double activeScore = redisTemplate.opsForZSet().score("q:active", userIdStr);
        if (activeScore != null) {
            // 진입 완료 상태면 JWT 토큰 발급
            String token = jwtProvider.generateToken(userId);
            return new QueueStatusResponse("ACTIVE", 0L, token);
        }

        // 대기열 상태
        Long rank = redisTemplate.opsForZSet().rank("q:waiting", userIdStr);
        if (rank != null) {
            return new QueueStatusResponse("WAITING", rank, null);
        }

        // 5분이 지나 만료 또는 튕긴 상태
        return new QueueStatusResponse("INVALID", -1L, null);
    }
}