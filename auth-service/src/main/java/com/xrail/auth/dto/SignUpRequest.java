package com.xrail.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$",
                 message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다") String password,
        @NotBlank @Size(max = 50) String name,
        String phone,
        @Pattern(regexp = "^\\d{8}$", message = "생년월일은 YYYYMMDD 형식이어야 합니다") String birthDate
) {}
