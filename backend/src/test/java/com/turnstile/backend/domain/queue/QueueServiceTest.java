package com.turnstile.backend.domain.queue;

import com.turnstile.backend.domain.auth.enums.TokenType;
import com.turnstile.backend.domain.auth.provider.JwtProvider;
import com.turnstile.backend.domain.concert.entity.Concert;
import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.queue.dto.QueueEntryRequest;
import com.turnstile.backend.domain.queue.dto.QueueStatusResponse;
import com.turnstile.backend.domain.queue.repository.QueueRedisRepository;
import com.turnstile.backend.domain.queue.service.QueueService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

/**
 * 대기열 서비스의 비즈니스 예외 처리 및 토큰 발급 로직을 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @InjectMocks private QueueService queueService;

    @Mock private QueueRedisRepository queueRedisRepository;
    @Mock private ConcertRepository concertRepository;
    @Mock private JwtProvider jwtProvider;

    @Test
    @DisplayName("예매 오픈 시간 전에 대기열 진입 시도 시 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_enter_queue_before_open_time() {
        // given: 내일 오픈하는 콘서트 세팅
        QueueEntryRequest request = new QueueEntryRequest(1L);
        Concert futureConcert = Concert.builder()
                .title("미래 콘서트")
                .ticketingStartAt(LocalDateTime.now().plusDays(1))
                .build();

        given(concertRepository.findById(1L)).willReturn(Optional.of(futureConcert));

        // when & then: TurnstileException 발생 및 에러 코드 검증
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> queueService.enterQueue(100L, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("ACTIVE 상태인 유저가 폴링 시, 고정된 만료 시간(Score) 기반의 토큰을 항상 동일하게 반환해야 한다")
    void should_return_consistent_token_when_user_is_active() {
        // given: 활성 유저의 고정된 타임스탬프(Score)와 기대 토큰 세팅
        Long concertId = 100L;
        Long userId = 1L;
        Double fixedScore = 1700000000.0;
        String expectedToken = "consistent-token";
        Date expectedDate = new Date(fixedScore.longValue() * 1000);

        given(queueRedisRepository.getActiveUserScore(String.valueOf(concertId), String.valueOf(userId)))
                .willReturn(fixedScore);
        given(jwtProvider.generateToken(userId, TokenType.QUEUE, expectedDate)).willReturn(expectedToken);

        // when: 두 번 연속 폴링 상태 조회
        QueueStatusResponse response1 = queueService.getQueueStatus(concertId, userId);
        QueueStatusResponse response2 = queueService.getQueueStatus(concertId, userId);

        // then: 동일한 토큰이 발급되며 상태가 ACTIVE인지 검증
        assertThat(response1.status()).isEqualTo("ACTIVE");
        assertThat(response1.accessToken()).isEqualTo(expectedToken);
        assertThat(response2.accessToken()).isEqualTo(expectedToken);
    }
}