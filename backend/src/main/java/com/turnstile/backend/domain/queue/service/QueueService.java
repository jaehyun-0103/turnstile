package com.turnstile.backend.domain.queue.service;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueEntryResponse;
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
    private final JwtProvider jwtProvider;

    // 스케줄러가 참조할 활성화된 콘서트 목록 키
    private static final String ACTIVE_CONCERTS_KEY = "q:concerts:active";

    // 대기열 진입
    public QueueEntryResponse enterQueue(QueueEntryRequest request) {
        String concertIdStr = String.valueOf(request.concertId());
        String userIdStr = String.valueOf(request.userId());

        String counterKey = "q:counter:" + concertIdStr;
        String waitingKey = "q:waiting:" + concertIdStr;
        String activeKey = "q:active:" + concertIdStr;

        // 이미 진입 완료 상태인지 확인
        Double activeScore = redisTemplate.opsForZSet().score(activeKey, userIdStr);
        if (activeScore != null) {
            return new QueueEntryResponse(request.concertId(), 0L, "ACTIVE");
        }

        // 스케줄러가 순회할 수 있도록 활성화된 콘서트 목록에 등록
        redisTemplate.opsForSet().add(ACTIVE_CONCERTS_KEY, concertIdStr);

        // Lua 스크립트를 통한 원자적 대기열 진입
        Long myScore = redisTemplate.execute(
                enterQueueScript,
                List.of(counterKey, waitingKey),
                userIdStr
        );

        // 내 앞의 대기자 수 계산
        Long rank = redisTemplate.opsForZSet().rank(waitingKey, userIdStr);
        if (rank == null) {
            rank = 0L;
        }

        return new QueueEntryResponse(request.concertId(), rank, "WAITING");
    }

    // 대기 상태 폴링
    public QueueStatusResponse getQueueStatus(Long concertId, Long userId) {
        String concertIdStr = String.valueOf(concertId);
        String userIdStr = String.valueOf(userId);

        String waitingKey = "q:waiting:" + concertIdStr;
        String activeKey = "q:active:" + concertIdStr;

        // 진입 완료 상태 확인
        Double activeScore = redisTemplate.opsForZSet().score(activeKey, userIdStr);
        if (activeScore != null) {
            // 진입 완료 상태면 JWT 토큰 발급
            String accessToken = jwtProvider.generateToken(userId);
            return new QueueStatusResponse(concertId, 0L, "ACTIVE", accessToken);
        }

        // 대기열 상태 확인
        Long rank = redisTemplate.opsForZSet().rank(waitingKey, userIdStr);
        if (rank != null) {
            return new QueueStatusResponse(concertId, rank, "WAITING", null);
        }

        // 5분이 지나 만료 또는 튕긴 상태
        return new QueueStatusResponse(concertId, -1L, "INVALID", null);
    }
}