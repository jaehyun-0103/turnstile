package com.turnstile.backend.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 로직 중 발생하는 커스텀 예외 처리 (TurnstileException)
     * 에러 코드에 정의된 HttpStatus와 메시지를 기반으로 응답합니다.
     */
    @ExceptionHandler(TurnstileException.class)
    protected ResponseEntity<ErrorResponse> handleTurnstileException(TurnstileException e) {
        ErrorCode errorCode = e.getErrorCode();

        // 방어 코드: errorCode가 null이면 500으로 처리
        if (errorCode == null) {
            log.error("[Unhandled Exception] TurnstileException thrown without ErrorCode", e);
            return handleException(e);
        }

        log.warn("Business Exception: [{} - {}] {}",
                errorCode.getCode(), errorCode.getStatus(), e.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    /**
     * 잘못된 URL 호출 예외 처리 (404 Not Found)
     * 클라이언트가 존재하지 않는 API 엔드포인트를 요청할 때 발생합니다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    protected ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("[Client Exception] NoResourceFoundException: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 지원하지 않는 HTTP 메서드 호출 예외 처리 (405 Method Not Allowed)
     * 엔드포인트는 존재하지만, GET/POST 등 요청 메서드가 일치하지 않을 때 발생합니다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ErrorResponse> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("[Client Exception] HttpRequestMethodNotSupportedException: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 정의되지 않은 모든 최상위 예외 처리 (500 Internal Server Error)
     * 시스템 보안을 위해 구체적인 에러 내용은 숨기고 '서버 내부 오류'로 응답합니다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[Unhandled Exception] Unexpected error occurred: ", e);

        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}