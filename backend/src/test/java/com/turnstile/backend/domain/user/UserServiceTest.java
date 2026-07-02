package com.turnstile.backend.domain.user;

import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
import com.turnstile.backend.domain.user.service.UserService;
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
 * 유저 서비스의 비즈니스 로직 및 자원 검증 예외 처리를 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private UserService userService;

    @Test
    @DisplayName("충전 금액이 0 이하일 경우 INVALID_INPUT_VALUE 예외가 발생해야 한다")
    void should_throw_exception_when_charge_amount_is_invalid() {
        // given: 유효하지 않은 충전 금액 세팅
        Long userId = 1L;
        Long invalidAmount = -5000L;

        // when & then: 예외 발생 및 에러 코드 검증
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> userService.chargeBalance(userId, invalidAmount));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("보유 잔액보다 많은 금액을 차감 시도할 경우 INSUFFICIENT_BALANCE 예외가 발생해야 한다")
    void should_throw_exception_when_balance_is_insufficient() {
        // given: 유저 정보 확보 및 차감 쿼리가 0건을 업데이트(실패)하도록 모킹
        Long userId = 1L;
        Long deductAmount = 10000L;
        User user = User.builder().id(userId).balance(5000L).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.deductBalance(userId, deductAmount)).willReturn(0);

        // when & then: 잔액 부족 예외 발생 검증
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> userService.deductBalance(userId, deductAmount));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    @DisplayName("존재하지 않는 유저의 잔액을 조회할 경우 USER_NOT_FOUND 예외가 발생해야 한다")
    void should_throw_exception_when_user_not_found_on_get_balance() {
        // given: 존재하지 않는 유저 모킹
        Long invalidUserId = 999L;
        given(userRepository.findById(invalidUserId)).willReturn(Optional.empty());

        // when & then: 유저 찾을 수 없음 예외 발생 검증
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> userService.getBalance(invalidUserId));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}