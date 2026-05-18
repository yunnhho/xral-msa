package com.xrail.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NonMemberRegisterRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = "^\\d{10,11}$") String phone,
        @NotBlank @Pattern(regexp = "^\\d{4,6}$") String password
) {}
