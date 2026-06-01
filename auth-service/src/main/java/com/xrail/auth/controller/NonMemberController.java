package com.xrail.auth.controller;

import com.xrail.auth.dto.NonMemberLoginRequest;
import com.xrail.auth.dto.NonMemberRegisterRequest;
import com.xrail.auth.dto.NonMemberResponse;
import com.xrail.auth.service.NonMemberService;
import com.xrail.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "NonMember", description = "비회원 인증 API")
@RestController
@RequestMapping("/api/auth/non-member")
@RequiredArgsConstructor
public class NonMemberController {

    private final NonMemberService nonMemberService;

    @Operation(summary = "비회원 등록", description = "이름·전화번호·비밀번호(4~6자리)로 비회원 등록. accessCode 반환.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NonMemberResponse> register(@Valid @RequestBody NonMemberRegisterRequest request) {
        return ApiResponse.ok(nonMemberService.register(request));
    }

    @Operation(summary = "비회원 로그인", description = "accessCode + phone + password 세 가지 일치 시 토큰 발급.")
    @PostMapping("/login")
    public ApiResponse<NonMemberResponse> login(@Valid @RequestBody NonMemberLoginRequest request,
                                                HttpServletRequest httpRequest) {
        return ApiResponse.ok(nonMemberService.login(request, httpRequest));
    }
}
