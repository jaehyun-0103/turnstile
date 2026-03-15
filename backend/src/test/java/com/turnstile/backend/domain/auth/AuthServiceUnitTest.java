package com.turnstile.backend.domain.auth;

import com.turnstile.backend.domain.auth.dto.LoginRequest;
import com.turnstile.backend.domain.auth.dto.LoginResponse;
import com.turnstile.backend.domain.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 인증 비즈니스 로직을 검증하는 단위 테스트입니다.
 */
class AuthServiceUnitTest {

    private final AuthService authService = new AuthService();

    @Test
    @DisplayName("로그인 요청 시 현재는 사전에 정의된 임시 사용자 정보를 반환한다")
    void loginReturnMockData() {
        // given: 로그인 요청 정보가 주어짐
        LoginRequest request = new LoginRequest("tester");

        // when: 로그인을 수행함
        LoginResponse response = authService.login(request);

        // then: 임시로 설정된 사용자 정보와 잔액을 확인함
        assertThat(response.username()).isEqualTo("tester");
        assertThat(response.name()).isEqualTo("Mock User");
        assertThat(response.userId()).isNotNull();
    }
}