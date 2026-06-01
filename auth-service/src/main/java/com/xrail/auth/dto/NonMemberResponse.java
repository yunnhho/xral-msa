package com.xrail.auth.dto;

public record NonMemberResponse(
        Long userId,
        String name,
        String role,
        String accessCode,
        String accessToken,
        String refreshToken
) {}
