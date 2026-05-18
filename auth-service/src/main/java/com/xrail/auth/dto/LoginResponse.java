package com.xrail.auth.dto;

public record LoginResponse(
        Long userId,
        String name,
        String role,
        String accessToken,
        String refreshToken
) {}
