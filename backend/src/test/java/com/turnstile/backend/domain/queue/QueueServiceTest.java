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
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("ACTIVE 상태면 Redis의 Score를 기반으로 고정된 만료 시간의 토큰 반환")
    void getQueueStatusReturnsTokenWhenActive() {
        // given: 활성 상태 유저를 위한 고정 타임스탬프(Score) 및 Mock 토큰 세팅
        Long concertId = 100L;
        Long userId = 1L;
        Double fixedScore = 1700000000.0; // 고정된 타임스탬프
        String fakeToken = "mocked-fixed-jwt-token";

        // given: Redis에서 해당 유저의 활성 스코어를 성공적으로 가져오는 상황 설정
        given(queueRedisRepository.getActiveUserScore(String.valueOf(concertId), String.valueOf(userId)))
                .willReturn(fixedScore);

        // given: 전달된 스코어 기반의 만료 시간을 사용하여 토큰이 생성되도록 Mock 설정
        given(jwtProvider.generateToken(eq(userId), any(Date.class)))
                .willReturn(fakeToken);

        // when: 대기열 상태 조회 요청 실행
        QueueStatusResponse response = queueService.getQueueStatus(concertId, userId);

        // then: 검증
        // 유저의 상태가 ACTIVE이며, 발급된 토큰이 Mocking된 값과 일치하는지 확인
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.accessToken()).isEqualTo(fakeToken);
    }

    @Test
    @DisplayName("재발급 방지 확인: 여러 번 호출해도 Redis의 Score가 같다면 토큰 생성에 쓰이는 만료 시간은 동일")
    void getQueueStatus_FixedTTL_Logic_Test() {
        // given: 토큰 생성을 위한 고정 스코어와 기대되는 Date(만료시간) 객체 세팅
        Long concertId = 100L;
        Long userId = 1L;
        Double fixedScore = 1700000000.0;
        Date expectedDate = new Date(fixedScore.longValue() * 1000);
        String expectedToken = "consistent-token";

        // given: Redis에서 항상 동일한 스코어를 반환하도록 설정
        given(queueRedisRepository.getActiveUserScore(String.valueOf(concertId), String.valueOf(userId)))
                .willReturn(fixedScore);

        // given: 정확히 계산된 expectedDate가 전달될 때만 고정된 토큰을 반환하도록 설정
        given(jwtProvider.generateToken(userId, expectedDate)).willReturn(expectedToken);

        // when: 첫 번째 조회와 두 번째 조회 수행
        QueueStatusResponse response1 = queueService.getQueueStatus(concertId, userId);
        QueueStatusResponse response2 = queueService.getQueueStatus(concertId, userId);

        // then: 검증
        // 첫 번째 응답 토큰 확인
        assertThat(response1.accessToken()).isEqualTo(expectedToken);

        // 두 번째 응답 토큰이 첫 번째와 완벽히 일치하는지 확인
        assertThat(response2.accessToken()).isEqualTo(expectedToken);
        assertThat(response1.accessToken()).isEqualTo(response2.accessToken()); // 토큰 값의 일관성 확인
    }
}