package com.turnstile.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 시스템 전역에서 사용하는 에러 코드와 HTTP 상태 코드, 응답 메시지를 정의하는 열거형입니다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통 및 인프라 장애
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 내부 오류가 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "허용되지 않은 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "요청한 리소스를 찾을 수 없습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "C005", "일시적으로 요청이 집중되었습니다. 잠시 후 다시 시도해주세요."),

    // 인증 및 사용자 관련
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U001", "사용자를 찾을 수 없습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "U002", "잔액이 부족합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A001", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "만료된 토큰입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A003", "해당 API에 대한 접근 권한이 없습니다."),

    // 대기열 도메인
    TICKETING_NOT_OPEN(HttpStatus.BAD_REQUEST, "Q001", "예매 오픈 전입니다."),
    QUEUE_NOT_ACTIVE(HttpStatus.FORBIDDEN, "Q002", "대기열 권한이 없거나 만료되었습니다."),

    // 콘서트 및 좌석 도메인
    CONCERT_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "존재하지 않는 콘서트입니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "S002", "존재하지 않는 회차입니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "S003", "존재하지 않는 좌석입니다."),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "S004", "이미 선택되었거나 판매 완료된 좌석입니다."),

    // 예약 및 결제 도메인
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "예약 정보를 찾을 수 없습니다."),
    ALREADY_PAID(HttpStatus.BAD_REQUEST, "P002", "이미 결제가 완료된 예약입니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "P003", "결제 금액이 일치하지 않습니다."),
    CONCURRENCY_CONFLICT(HttpStatus.CONFLICT, "P004", "동시 요청으로 인해 처리에 실패했습니다."),
    FORBIDDEN_USER(HttpStatus.FORBIDDEN, "P005", "결제 권한이 없습니다."),
    ALREADY_HAS_PENDING_RESERVATION(HttpStatus.BAD_REQUEST, "P006", "이미 결제 대기 중인 예약 건이 존재합니다."),
    INVALID_SEAT_HOLD(HttpStatus.CONFLICT, "P007", "유효한 좌석 점유를 확인할 수 없습니다.");;

    private final HttpStatus status;
    private final String code;
    private final String message;
}