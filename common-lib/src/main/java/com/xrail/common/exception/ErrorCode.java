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
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "DUPLICATE_LOGIN_ID", "이미 사용 중인 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "유효하지 않은 리프레시 토큰입니다."),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "이미 폐기된 토큰입니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_REQUEST, "OAUTH_PROVIDER_ERROR", "OAuth 공급자 인증에 실패했습니다."),

    // Train
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "SEAT_ALREADY_TAKEN", "이미 예약된 좌석입니다."),
    LATE_RESERVATION(HttpStatus.UNPROCESSABLE_ENTITY, "LATE_RESERVATION", "이미 출발했거나 출발 시각이 임박하여 예약할 수 없습니다."),
    STATION_NOT_IN_ROUTE(HttpStatus.UNPROCESSABLE_ENTITY, "STATION_NOT_IN_ROUTE", "노선에 포함되지 않은 역입니다."),
    INVALID_ROUTE(HttpStatus.BAD_REQUEST, "INVALID_ROUTE", "출발역과 도착역이 동일하거나 유효하지 않은 노선입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."),
    RESERVATION_EXPIRED(HttpStatus.GONE, "RESERVATION_EXPIRED", "예약 가능 시간이 만료되었습니다."),

    // Payment
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_FAILED", "결제 처리에 실패했습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "중복된 요청입니다."),
    COUPON_INVALID(HttpStatus.BAD_REQUEST, "COUPON_INVALID", "유효하지 않은 쿠폰 코드입니다."),

    // Queue
    QUEUE_NOT_ACTIVE(HttpStatus.FORBIDDEN, "QUEUE_NOT_ACTIVE", "대기열 토큰이 누락되었거나 만료되었습니다."),
    QUEUE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "QUEUE_TOKEN_INVALID", "유효하지 않은 큐 토큰입니다."),
    INVALID_QUEUE_SCOPE(HttpStatus.BAD_REQUEST, "INVALID_QUEUE_SCOPE", "허용되지 않은 대기열 scope입니다."),

    // Gateway
    CAPTCHA_FAILED(HttpStatus.UNAUTHORIZED, "CAPTCHA_FAILED", "CAPTCHA 검증에 실패했습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // Common
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
