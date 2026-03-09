package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;

@SpringBootTest
class QueueServiceCacheTest {

    @Autowired private QueueService queueService;
    @Autowired private CacheManager cacheManager;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @MockitoSpyBean private ConcertRepository concertRepository;
    @MockitoBean private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        // 테스트 독립성 보장을 위해 Redis와 Cache 전체 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("캐시 검증: 같은 ID로 진입 시 첫 번째만 DB를 조회하고 나머지는 캐시")
    void verifyEarlyReturnCaching() {
        // given: 이미 티켓팅이 시작된 콘서트로 가짜 응답 세팅
        Long concertId = 1L;
        Concert mockConcert = Concert.builder()
                .id(concertId)
                .title("아이유 콘서트")
                .ticketingStartAt(LocalDateTime.now().minusDays(1))
                .build();

        // SpyBean을 이용해 실제 Bean을 사용하되 findById만 stub 처리
        doReturn(Optional.of(mockConcert)).when(concertRepository).findById(concertId);

        QueueEntryRequest request = new QueueEntryRequest(100L, concertId);

        // when: 동일한 콘서트에 대해 3번 연속 대기열 진입 시도
        queueService.enterQueue(request); // 첫 호출 -> 캐시 없음 -> DB 조회
        queueService.enterQueue(request); // 두 번째 호출 -> 캐시 사용
        queueService.enterQueue(request); // 세 번째 호출 -> 캐시 사용

        // then: Repository 조회는 최초 1회만 발생
        verify(concertRepository, times(1)).findById(concertId);
    }
}