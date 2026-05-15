package com.xrail.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),

    // Train
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "SEAT_ALREADY_TAKEN", "이미 예약된 좌석입니다."),
    LATE_RESERVATION(HttpStatus.UNPROCESSABLE_ENTITY, "LATE_RESERVATION", "출발 시각이 임박하여 예약할 수 없습니다."),
    STATION_NOT_IN_ROUTE(HttpStatus.UNPROCESSABLE_ENTITY, "STATION_NOT_IN_ROUTE", "노선에 포함되지 않은 역입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."),
    RESERVATION_EXPIRED(HttpStatus.GONE, "RESERVATION_EXPIRED", "만료된 예약입니다."),

    // Payment
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_FAILED", "결제 처리에 실패했습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "중복된 요청입니다."),

    // Queue
    QUEUE_NOT_ACTIVE(HttpStatus.FORBIDDEN, "QUEUE_NOT_ACTIVE", "대기열 토큰이 누락되었거나 만료되었습니다."),
    QUEUE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "QUEUE_TOKEN_INVALID", "유효하지 않은 큐 토큰입니다."),

    // Gateway
    CAPTCHA_FAILED(HttpStatus.UNAUTHORIZED, "CAPTCHA_FAILED", "CAPTCHA 검증에 실패했습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 횟수를 초과했습니다."),

    // Common
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
