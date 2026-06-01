package com.xrail.auth.controller;

import com.xrail.auth.dto.*;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.auth.service.AuthService;
import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "회원 인증 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    @Operation(summary = "회원가입", description = "이메일/비밀번호로 일반 회원 가입. 성공 시 내 정보를 반환.")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.ok(authService.signUp(request));
    }

    @Operation(summary = "로그인", description = "loginId + password 인증. Access/Refresh 토큰 반환.")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, httpRequest));
    }

    @Operation(summary = "토큰 갱신", description = "Refresh Token으로 Access Token 재발급 (토큰 회전).")
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.refresh(request, httpRequest));
    }

    @Operation(summary = "로그아웃", description = "Authorization Bearer 헤더의 JWT로 Redis refresh 토큰 폐기.")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            Claims claims = jwtTokenProvider.parseAndValidate(authorization.substring(7));
            Long userId = Long.parseLong(claims.getSubject());
            authService.logout(userId);
        } catch (JwtException | NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok();
    }

    @Operation(summary = "내 정보 조회", description = "Authorization Bearer 헤더의 JWT로 내 정보 반환.")
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            Claims claims = jwtTokenProvider.parseAndValidate(authorization.substring(7));
            Long userId = Long.parseLong(claims.getSubject());
            return ApiResponse.ok(authService.getMe(userId));
        } catch (JwtException | NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
