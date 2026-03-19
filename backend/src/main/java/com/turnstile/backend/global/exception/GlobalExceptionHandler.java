package com.turnstile.backend.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 시스템 전역에서 발생하는 예외를 가로채어 공통된 응답 규격으로 변환하는 핸들러입니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 로직 수행 중 발생하는 도메인 전용 예외를 처리합니다.
     */
    @ExceptionHandler(TurnstileException.class)
    protected ResponseEntity<ErrorResponse> handleTurnstileException(TurnstileException e) {
        ErrorCode errorCode = e.getErrorCode();

        if (errorCode == null) {
            log.error("[Global] Internal Error | TurnstileException thrown without ErrorCode (message: {})", e.getMessage());
            return handleException(e);
        }

        log.warn("[Global] Business Exception | Fail - Code: {} (message: {})", errorCode.getCode(), e.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    /**
     * 클라이언트가 존재하지 않는 API 엔드포인트 예외를 404 에러로 응답합니다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    protected ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("[Global] Client Exception | Fail - Code: {} (url: {})", ErrorCode.RESOURCE_NOT_FOUND.getCode(), e.getMessage());

        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 엔드포인트는 존재하지만, GET/POST 등 요청 메서드가 일치하지 않은 예외를 405 에러로 응답합니다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ErrorResponse> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("[Global] Client Exception | Fail - Code: {} (method: {})", ErrorCode.METHOD_NOT_ALLOWED.getCode(), e.getMethod());

        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * @Valid 어노테이션에 의한 DTO 파라미터 유효성 검증 실패 예외를 처리합니다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_INPUT_VALUE.getMessage());

        log.warn("[Global] Validation Exception | Fail - Code: {} (message: {})", ErrorCode.INVALID_INPUT_VALUE.getCode(), errorMessage);

        // ErrorResponse의 Builder를 사용하여 구체적인 에러 메시지 주입
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ErrorResponse.builder()
                        .status(ErrorCode.INVALID_INPUT_VALUE.getStatus().value())
                        .code(ErrorCode.INVALID_INPUT_VALUE.getCode())
                        .message(errorMessage)
                        .build());
    }

    /**
     * 처리되지 않은 최상위 시스템 예외를 500 에러로 응답합니다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[Global] Internal Error | Unexpected error occurred (message: {})", e.getMessage(), e);

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}