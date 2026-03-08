package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.global.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

@DataRedisTest
@Import({RedisConfig.class, QueueRedisRepository.class, QueueService.class})
class QueueServicePollingTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private ConcertRepository concertRepository;

    private static final Long CONCERT_ID = 1L;
    private static final String WAITING_QUEUE_KEY = "q:waiting:" + CONCERT_ID;
    private static final String ACTIVE_QUEUE_KEY = "q:active:" + CONCERT_ID;

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장을 위해 Redis 전체 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("폴링: 진입 완료 상태면 토큰, 대기 상태면 남은 순번 반환")
    void getQueueStatusTest() {
        // given
        given(jwtProvider.generateToken(anyLong())).willReturn("mock-access-token");

        // given: 대기 상태 유저 2명 세팅
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "10", 1);
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "20", 2);

        // given: 진입 완료 상태 유저 1명 세팅
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", 1700000000);

        // when & then 1: 진입 완료 유저 폴링
        QueueStatusResponse activeRes = queueService.getQueueStatus(CONCERT_ID, 99L);
        assertThat(activeRes.status()).isEqualTo("ACTIVE");
        assertThat(activeRes.rank()).isEqualTo(0L);
        assertThat(activeRes.accessToken()).isEqualTo("mock-access-token");

        // when & then 2: 대기열 중간 유저 폴링
        QueueStatusResponse waitingRes = queueService.getQueueStatus(CONCERT_ID, 20L);
        assertThat(waitingRes.status()).isEqualTo("WAITING");
        assertThat(waitingRes.rank()).isEqualTo(1L);
        assertThat(waitingRes.accessToken()).isNull();

        // when & then 3: 줄 안 선 유저 폴링
        QueueStatusResponse invalidRes = queueService.getQueueStatus(CONCERT_ID, 777L);
        assertThat(invalidRes.status()).isEqualTo("INVALID");
        assertThat(invalidRes.rank()).isEqualTo(-1L);
    }
}