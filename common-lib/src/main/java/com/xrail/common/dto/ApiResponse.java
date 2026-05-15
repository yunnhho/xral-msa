package com.xrail.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;
    private final String timestamp;

    private ApiResponse(boolean success, String code, String message, T data) {
        this.success   = success;
        this.code      = code;
        this.message   = message;
        this.data      = data;
        this.timestamp = Instant.now().toString();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "성공", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, "OK", "성공", null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}
