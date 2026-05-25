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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.ok(authService.signUp(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, httpRequest));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.refresh(request, httpRequest));
    }

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

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@RequestHeader(Headers.USER_ID) Long userId) {
        return ApiResponse.ok(authService.getMe(userId));
    }
}
