package com.turnstile.backend.global.exception;

import lombok.Getter;

/**
 * 프로젝트 비즈니스 로직 전반에서 사용하는 최상위 커스텀 예외 클래스입니다.
 */
@Getter
public class TurnstileException extends RuntimeException {
    private final ErrorCode errorCode;

    public TurnstileException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 로그 기록을 위해 표준 메시지 외에 구체적인 상세 내용을 포함하여 예외를 생성합니다.
     * @param errorCode 사전 정의된 에러 코드 객체
     * @param detailMessage 디버깅을 위한 상세 에러 메시지
     */
    public TurnstileException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }
}