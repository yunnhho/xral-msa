package com.xrail.auth.service;

import com.xrail.auth.dto.NonMemberLoginRequest;
import com.xrail.auth.dto.NonMemberRegisterRequest;
import com.xrail.auth.dto.NonMemberResponse;
import com.xrail.auth.entity.NonMember;
import com.xrail.auth.kafka.AuthEventProducer;
import com.xrail.auth.repository.NonMemberRepository;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class NonMemberService {

    private static final String NANOID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ACCESS_CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NonMemberRepository nonMemberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuthEventProducer authEventProducer;

    @Transactional
    public NonMemberResponse register(NonMemberRegisterRequest request) {
        String accessCode = generateAccessCode();
        NonMember nonMember = nonMemberRepository.save(NonMember.builder()
                .name(request.name())
                .phone(request.phone())
                .password(passwordEncoder.encode(request.password()))
                .accessCode(accessCode)
                .build());

        authEventProducer.publishUserSignedUp(nonMember);

        String access = jwtTokenProvider.issueAccessToken(nonMember.getUserId(), nonMember.getRole(), nonMember.getName());
        String refresh = jwtTokenProvider.issueRefreshToken(nonMember.getUserId());
        return new NonMemberResponse(nonMember.getUserId(), nonMember.getName(), accessCode, access, refresh);
    }

    @Transactional(readOnly = true)
    public NonMemberResponse login(NonMemberLoginRequest request, HttpServletRequest httpRequest) {
        NonMember nonMember = nonMemberRepository.findByAccessCodeAndPhone(request.accessCode(), request.phone())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(request.password(), nonMember.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String access = jwtTokenProvider.issueAccessToken(nonMember.getUserId(), nonMember.getRole(), nonMember.getName());
        String refresh = jwtTokenProvider.issueRefreshToken(nonMember.getUserId());
        refreshTokenService.save(nonMember.getUserId(), refresh,
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"), null);
        return new NonMemberResponse(nonMember.getUserId(), nonMember.getName(), nonMember.getAccessCode(), access, refresh);
    }

    private String generateAccessCode() {
        StringBuilder sb = new StringBuilder(ACCESS_CODE_LENGTH);
        for (int i = 0; i < ACCESS_CODE_LENGTH; i++) {
            sb.append(NANOID_CHARS.charAt(RANDOM.nextInt(NANOID_CHARS.length())));
        }
        return sb.toString();
    }
}
