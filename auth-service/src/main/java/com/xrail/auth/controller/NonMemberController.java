package com.xrail.auth.controller;

import com.xrail.auth.dto.NonMemberLoginRequest;
import com.xrail.auth.dto.NonMemberRegisterRequest;
import com.xrail.auth.dto.NonMemberResponse;
import com.xrail.auth.service.NonMemberService;
import com.xrail.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/non-member")
@RequiredArgsConstructor
public class NonMemberController {

    private final NonMemberService nonMemberService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NonMemberResponse> register(@Valid @RequestBody NonMemberRegisterRequest request) {
        return ApiResponse.ok(nonMemberService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<NonMemberResponse> login(@Valid @RequestBody NonMemberLoginRequest request,
                                                HttpServletRequest httpRequest) {
        return ApiResponse.ok(nonMemberService.login(request, httpRequest));
    }
}
