package com.turnstile.backend.global.exception;

import lombok.Getter;

@Getter
public class TurnstileException extends RuntimeException {
    private final ErrorCode errorCode;

    public TurnstileException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public TurnstileException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }
}