package com.turnstile.backend.global;

import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.ErrorResponse;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionInfrastructureTest {

    @Test
    @DisplayName("TurnstileException은 생성 시 주입된 ErrorCode 정보를 정확히 보유")
    void turnstileExceptionShouldHoldErrorCode() {
        // given
        ErrorCode errorCode = ErrorCode.INSUFFICIENT_BALANCE;

        // when
        TurnstileException exception = new TurnstileException(errorCode);

        // then
        assertThat(exception.getErrorCode()).isEqualTo(errorCode);
        assertThat(exception.getMessage()).isEqualTo(errorCode.getMessage());
    }

    @Test
    @DisplayName("ErrorResponse.of()는 ErrorCode의 정보를 바탕으로 정확한 응답 객체 생성")
    void errorResponseOfShouldMapCorrectly() {
        // given
        ErrorCode errorCode = ErrorCode.SEAT_ALREADY_HELD;

        // when
        ErrorResponse response = ErrorResponse.of(errorCode);

        // then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getCode()).isEqualTo("S004");
        assertThat(response.getMessage()).isEqualTo("이미 선택된 좌석입니다.");
    }
}