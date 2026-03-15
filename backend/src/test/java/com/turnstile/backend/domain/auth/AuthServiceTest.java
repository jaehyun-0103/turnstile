package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import com.turnstile.backend.domain.auth.service.AuthService;
import com.turnstile.backend.domain.user.entity.User;
import com.turnstile.backend.domain.user.repository.UserRepository;
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
 * 사용자 인증 및 로그인 비즈니스 로직을 검증하는 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private AuthService authService;

    @Test
    @DisplayName("가입된 유저명으로 로그인 요청 시, 유저 정보를 조회하여 반환해야 한다")
    void should_return_user_info_when_login_with_valid_username() {
        // given: 정상적인 로그인 요청과 DB에 존재하는 유저 모킹
        String targetUsername = "tester";
        LoginRequest request = new LoginRequest(targetUsername);

        User mockUser = User.builder()
                .id(1L)
                .username(targetUsername)
                .name("실제 유저")
                .balance(50000L)
                .build();

        given(userRepository.findByUsername(targetUsername)).willReturn(Optional.of(mockUser));

        // when: 로그인을 수행함
        LoginResponse response = authService.login(request);

        // then: DB에서 조회한 실제 정보가 정확히 매핑되었는지 확인
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo(targetUsername);
        assertThat(response.name()).isEqualTo("실제 유저");
        assertThat(response.balance()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("가입되지 않은 유저명으로 로그인 요청 시, USER_NOT_FOUND 예외가 발생해야 한다")
    void should_throw_exception_when_login_with_invalid_username() {
        // given: DB에 존재하지 않는 유저명 세팅
        String invalidUsername = "unknown_user";
        LoginRequest request = new LoginRequest(invalidUsername);

        given(userRepository.findByUsername(invalidUsername)).willReturn(Optional.empty());

        // when & then: 로그인 시도 시 예외 발생 검증
        TurnstileException exception = assertThrows(TurnstileException.class,
                () -> authService.login(request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}