package com.turnstile.backend.domain.concert;

import com.turnstile.backend.domain.concert.repository.ConcertRepository;
import com.turnstile.backend.domain.concert.repository.ConcertScheduleRepository;
import com.turnstile.backend.domain.concert.repository.SeatRepository;
import com.turnstile.backend.domain.concert.service.ConcertService;
import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

/**
 * 콘서트 서비스의 비즈니스 로직 및 자원 검증 예외 처리를 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class ConcertServiceTest {

    @Mock private ConcertRepository concertRepository;
    @Mock private ConcertScheduleRepository concertScheduleRepository;

    @InjectMocks private ConcertService concertService;

    @Test
    @DisplayName("존재하지 않는 콘서트 회차 조회 시 CONCERT_NOT_FOUND 예외가 발생해야 한다")
    void should_throw_exception_when_concert_not_found() {
        // given: 존재하지 않는 콘서트 식별자 세팅
        Long invalidConcertId = 999L;
        given(concertRepository.findById(invalidConcertId)).willReturn(Optional.empty());

        // when & then: 조회 시 예외 발생 확인
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> concertService.getSchedules(invalidConcertId));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCERT_NOT_FOUND);
    }

    @Test
    @DisplayName("존재하지 않는 회차의 좌석 조회 시 SCHEDULE_NOT_FOUND 예외가 발생해야 한다")
    void should_throw_exception_when_schedule_not_found() {
        // given: 존재하지 않는 회차 식별자 세팅
        Long invalidScheduleId = 999L;
        given(concertScheduleRepository.findById(invalidScheduleId)).willReturn(Optional.empty());

        // when & then: 조회 시 예외 발생 확인
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> concertService.getSeats(invalidScheduleId));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }
}