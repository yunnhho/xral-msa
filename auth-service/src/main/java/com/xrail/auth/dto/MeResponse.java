package com.xrail.auth.dto;

public record MeResponse(
        Long userId,
        String name,
        String email,
        String role
) {}
