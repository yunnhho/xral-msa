package com.xrail.gateway.dto;

public record JwtClaims(
        String userId,
        String role,
        String name
) {}
