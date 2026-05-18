package com.xrail.auth.controller;

import com.xrail.auth.dto.*;
import com.xrail.auth.service.AuthService;
import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
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
    public ApiResponse<Void> logout(@RequestHeader(Headers.USER_ID) Long userId) {
        authService.logout(userId);
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@RequestHeader(Headers.USER_ID) Long userId) {
        return ApiResponse.ok(authService.getMe(userId));
    }
}
