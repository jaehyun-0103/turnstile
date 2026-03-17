package com.turnstile.backend.global;

import com.turnstile.backend.global.exception.ErrorCode;
import com.turnstile.backend.global.exception.ErrorResponse;
import com.turnstile.backend.global.exception.TurnstileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예외 처리 기반 인프라의 명세와 객체 매핑 동작을 검증하는 테스트입니다.
 */
class ExceptionInfrastructureTest {

    @Test
    @DisplayName("TurnstileException은 생성 시 주입된 ErrorCode 정보를 정확히 보유한다")
    void turnstileExceptionShouldHoldErrorCode() {
        // given: 특정 도메인의 에러 코드가 주어짐
        ErrorCode errorCode = ErrorCode.INSUFFICIENT_BALANCE;

        // when: 예외 객체를 생성함
        TurnstileException exception = new TurnstileException(errorCode);

        // then: 주입된 에러 코드와 기본 메시지가 일치해야 함
        assertThat(exception.getErrorCode()).isEqualTo(errorCode);
        assertThat(exception.getMessage()).isEqualTo(errorCode.getMessage());
    }

    @Test
    @DisplayName("상세 메시지를 포함한 TurnstileException은 주입된 메시지를 우선적으로 보유한다")
    void turnstileExceptionShouldHoldDetailMessage() {
        // given: 에러 코드와 디버깅용 상세 메시지가 주어짐
        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
        String detailMessage = "입력 필드가 누락되었습니다.";

        // when: 상세 메시지를 포함하여 예외를 생성함
        TurnstileException exception = new TurnstileException(errorCode, detailMessage);

        // then: 에러 코드는 유지되나 메시지는 상세 메시지로 대체되어야 함
        assertThat(exception.getErrorCode()).isEqualTo(errorCode);
        assertThat(exception.getMessage()).isEqualTo(detailMessage);
    }

    @Test
    @DisplayName("ErrorResponse.of()는 ErrorCode 정보를 바탕으로 규격에 맞는 응답 객체를 생성한다")
    void errorResponseOfShouldMapCorrectly() {
        // given: 좌석 점유 충돌 에러 코드가 주어짐
        ErrorCode errorCode = ErrorCode.SEAT_ALREADY_HELD;

        // when: 응답 DTO로 변환함
        ErrorResponse response = ErrorResponse.of(errorCode);

        // then: HTTP 상태 코드, 내부 에러 코드, 메시지가 명세와 일치해야 함
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getCode()).isEqualTo("S004");
        assertThat(response.getMessage()).isEqualTo("이미 선택된 좌석입니다.");
    }
}