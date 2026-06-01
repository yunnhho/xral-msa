package com.xrail.auth.service;

import com.xrail.auth.dto.*;
import com.xrail.auth.entity.Member;
import com.xrail.auth.entity.enums.UserRole;
import com.xrail.auth.kafka.AuthEventProducer;
import com.xrail.auth.repository.MemberRepository;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuthEventProducer authEventProducer;

    @Transactional
    public MeResponse signUp(SignUpRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        Member member = memberRepository.save(Member.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name())
                .phone(request.phone())
                .birthDate(request.birthDate())
                .role(UserRole.ROLE_MEMBER)
                .build());

        authEventProducer.publishUserSignedUp(member);
        return new MeResponse(member.getUserId(), member.getName(), member.getEmail(), member.getRole().name());
    }

    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getPasswordHash() == null || !passwordEncoder.matches(request.password(), member.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String access = jwtTokenProvider.issueAccessToken(member.getUserId(), member.getRole(), member.getName());
        String refresh = jwtTokenProvider.issueRefreshToken(member.getUserId());
        refreshTokenService.save(member.getUserId(), refresh,
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"), null);

        return new LoginResponse(member.getUserId(), member.getName(), member.getRole().name(), access, refresh);
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest request, HttpServletRequest httpRequest) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(request.refreshToken());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!jwtTokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Long oldTokenId = refreshTokenService.rotate(request.refreshToken());

        Long userId = Long.parseLong(claims.getSubject());
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String newRefresh = jwtTokenProvider.issueRefreshToken(userId);
        refreshTokenService.save(userId, newRefresh,
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"), oldTokenId);

        String newAccess = jwtTokenProvider.issueAccessToken(userId, member.getRole(), member.getName());
        return new LoginResponse(userId, member.getName(), member.getRole().name(), newAccess, newRefresh);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenService.revokeAll(userId);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return new MeResponse(member.getUserId(), member.getName(), member.getEmail(), member.getRole().name());
    }
}
