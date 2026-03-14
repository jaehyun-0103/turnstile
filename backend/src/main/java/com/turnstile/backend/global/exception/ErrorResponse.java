package com.turnstile.backend.global.exception;

import lombok.Builder;
import lombok.Getter;

/**
 * 예외 발생 시 클라이언트에게 반환할 공통 에러 응답 구조를 정의하는 데이터 객체입니다.
 */
@Getter
@Builder
public class ErrorResponse {
    private final int status;
    private final String code;
    private final String message;

    /**
     * 에러 코드를 바탕으로 응답 객체를 생성합니다.
     * @param errorCode 사전 정의된 에러 코드 객체
     * @return 생성된 에러 응답 객체
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
                .status(errorCode.getStatus().value())
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
    }
}