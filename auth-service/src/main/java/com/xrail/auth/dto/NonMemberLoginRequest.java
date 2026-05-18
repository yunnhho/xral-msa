package com.xrail.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record NonMemberLoginRequest(
        @NotBlank String accessCode,
        @NotBlank String phone,
        @NotBlank String password
) {}
