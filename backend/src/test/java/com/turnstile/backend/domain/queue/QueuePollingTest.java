package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.JwtProvider;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
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
@Import({RedisConfig.class, QueueService.class})
class QueuePollingTest {

    @Autowired private QueueService queueService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoBean
    private JwtProvider jwtProvider;

    private static final String WAITING_QUEUE_KEY = "q:waiting";
    private static final String ACTIVE_QUEUE_KEY = "q:active";

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("폴링: 진입 완료 상태면 토큰, 대기 상태면 남은 순번 반환")
    void getQueueStatusTest() {
        // given: JWT 토큰 세팅
        given(jwtProvider.generateToken(anyLong())).willReturn("mock-token-1234");

        // given: 대기 상태 유저 3명 세팅
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "10", 1);
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "20", 2);
        redisTemplate.opsForZSet().add(WAITING_QUEUE_KEY, "30", 3);

        // given: 진입 완료 상태 유저 1명 세팅
        redisTemplate.opsForZSet().add(ACTIVE_QUEUE_KEY, "99", 1700000000);

        // when & then 1: 진입 완료 유저 폴링
        QueueStatusResponse activeRes = queueService.getQueueStatus(99L);
        assertThat(activeRes.status()).isEqualTo("ACTIVE");
        assertThat(activeRes.rank()).isEqualTo(0L);
        assertThat(activeRes.token()).isEqualTo("mock-token-1234");

        // when & then 2: 대기열 중간 유저 폴링
        QueueStatusResponse waitingRes = queueService.getQueueStatus(20L);
        assertThat(waitingRes.status()).isEqualTo("WAITING");
        assertThat(waitingRes.rank()).isEqualTo(1L); // "10"이 앞에 있으니 내 앞 대기자는 1명
        assertThat(waitingRes.token()).isNull();

        // when & then 3: 줄 안 선 유저 폴링
        QueueStatusResponse notFoundRes = queueService.getQueueStatus(777L);
        assertThat(notFoundRes.status()).isEqualTo("INVALID");
        assertThat(notFoundRes.rank()).isEqualTo(-1L);
    }
}