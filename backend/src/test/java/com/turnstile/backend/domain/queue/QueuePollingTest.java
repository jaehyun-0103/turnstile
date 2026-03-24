package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Redis 데이터 상태에 따른 대기열 폴링(Polling) 응답을 검증하는 통합 테스트입니다.
 */
@DataRedisTest
@Import({RedisConfig.class, QueueRedisRepository.class, QueueService.class})
class QueuePollingTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private ConcertRepository concertRepository;

    private static final Long CONCERT_ID = 1L;
    private static final String WAITING_QUEUE_KEY = "queue:concert:" + CONCERT_ID + ":waiting";
    private static final String ACTIVE_QUEUE_KEY = "queue:concert:" + CONCERT_ID + ":active";

    @BeforeEach
    void setUp() {
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("Redis 데이터 상태에 따라 ACTIVE, WAITING, INVALID 응답을 정확히 반환해야 한다")
    void should_return_correct_status_based_on_redis_state() {
        // given: JWT 토큰 모킹 및 Redis에 대기/활성 유저 세팅
        given(jwtProvider.generateToken(anyLong(), eq(TokenType.QUEUE), any(Date.class))).willReturn("mock-access-token");

        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "10", 1); // 대기 1순위
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "20", 2); // 대기 2순위
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", 1700000000); // 활성(입장 완료)

        // when & then 1: 진입 완료 유저 폴링 -> ACTIVE 반환
        QueueStatusResponse activeRes = queueService.getQueueStatus(CONCERT_ID, 99L);
        assertThat(activeRes.status()).isEqualTo("ACTIVE");
        assertThat(activeRes.accessToken()).isEqualTo("mock-access-token");

        // when & then 2: 대기열 중간 유저 폴링 -> WAITING 반환
        QueueStatusResponse waitingRes = queueService.getQueueStatus(CONCERT_ID, 20L);
        assertThat(waitingRes.status()).isEqualTo("WAITING");
        assertThat(waitingRes.rank()).isEqualTo(1L); // 0부터 시작하므로 2순위는 rank 1

        // when & then 3: 대기열에 없는 유저 폴링 -> INVALID 반환
        QueueStatusResponse invalidRes = queueService.getQueueStatus(CONCERT_ID, 777L);
        assertThat(invalidRes.status()).isEqualTo("INVALID");
        assertThat(invalidRes.rank()).isEqualTo(-1L);
    }
}