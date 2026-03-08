package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @InjectMocks private QueueService queueService;

    @Mock private QueueRedisRepository queueRedisRepository;
    @Mock private ConcertRepository concertRepository;
    @Mock private JwtProvider jwtProvider;

    @Test
    @DisplayName("콘서트 예매 오픈 시간 전에는 대기열 진입 시 예외 발생")
    void enterQueueFailsBeforeOpenTime() {
        // given: 내일 오픈하는 콘서트 세팅
        QueueEntryRequest request = new QueueEntryRequest(1L, 100L);
        Concert futureConcert = Concert.builder()
                .title("미래 콘서트")
                .ticketingStartAt(LocalDateTime.now().plusDays(1)) // 내일 오픈
                .build();

        given(concertRepository.findById(100L)).willReturn(Optional.of(futureConcert));

        // when & then: IllegalStateException 발생 검증
        assertThatThrownBy(() -> queueService.enterQueue(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("아직 예매가 오픈되지 않았습니다.");
    }

    @Test
    @DisplayName("대기 상태 폴링 시, ACTIVE 상태면 JWT 토큰을 발급하여 반환")
    void getQueueStatusReturnsTokenWhenActive() {
        // given: 유저의 상태를 ACTIVE로 Mocking
        Long concertId = 100L;
        Long userId = 1L;
        String fakeToken = "mocked-jwt-token";

        given(queueRedisRepository.isActiveUser(String.valueOf(concertId), String.valueOf(userId)))
                .willReturn(true);
        given(jwtProvider.generateToken(userId)).willReturn(fakeToken);

        // when: 폴링 요청
        QueueStatusResponse response = queueService.getQueueStatus(concertId, userId);

        // then: 토큰이 정상적으로 담겨 ACTIVE 상태로 반환되는지 확인
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.accessToken()).isEqualTo(fakeToken);
        assertThat(response.rank()).isEqualTo(0L);
    }
}