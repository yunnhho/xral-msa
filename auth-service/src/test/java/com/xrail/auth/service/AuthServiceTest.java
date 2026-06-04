package com.xrail.auth.service;

import com.xrail.auth.dto.LoginRequest;
import com.xrail.auth.dto.LoginResponse;
import com.xrail.auth.dto.MeResponse;
import com.xrail.auth.dto.RefreshRequest;
import com.xrail.auth.dto.SignUpRequest;
import com.xrail.auth.entity.Member;
import com.xrail.auth.entity.NonMember;
import com.xrail.auth.entity.enums.UserRole;
import com.xrail.auth.kafka.AuthEventProducer;
import com.xrail.auth.repository.MemberRepository;
import com.xrail.auth.repository.NonMemberRepository;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private NonMemberRepository nonMemberRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuthEventProducer authEventProducer;

    @InjectMocks
    private AuthService authService;

    private Member mockMember;

    @BeforeEach
    void setUp() {
        mockMember = Member.builder()
                .email("test@example.com")
                .passwordHash("hashed")
                .name("홍길동")
                .phone("01012345678")
                .birthDate("19900101")
                .role(UserRole.ROLE_MEMBER)
                .build();
    }

    // ===== signUp =====

    @Test
    void signUp_success() {
        when(memberRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(memberRepository.save(any(Member.class))).thenReturn(mockMember);

        SignUpRequest request = new SignUpRequest("test@example.com", "P@ssw0rd1!", "홍길동",
                "01012345678", "19900101");
        MeResponse response = authService.signUp(request);

        assertThat(response.name()).isEqualTo("홍길동");
        assertThat(response.email()).isEqualTo("test@example.com");
        verify(authEventProducer).publishUserSignedUp(any(Member.class));
    }

    @Test
    void signUp_duplicateEmail_throwsDuplicateEmail() {
        when(memberRepository.existsByEmail("test@example.com")).thenReturn(true);

        SignUpRequest request = new SignUpRequest("test@example.com", "P@ssw0rd1!", "홍길동",
                "01012345678", "19900101");

        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_EMAIL));

        verify(memberRepository, never()).save(any());
    }

    // ===== login =====

    @Test
    void login_success() {
        when(memberRepository.findByEmail("test@example.com")).thenReturn(Optional.of(mockMember));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtTokenProvider.issueAccessToken(any(), any(), any())).thenReturn("access-token");
        when(jwtTokenProvider.issueRefreshToken(any())).thenReturn("refresh-token");

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        LoginRequest request = new LoginRequest("test@example.com", "password123");
        LoginResponse response = authService.login(request, httpRequest);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void login_userNotFound_throwsUserNotFound() {
        when(memberRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@example.com", "pw"), mock(HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        when(memberRepository.findByEmail("test@example.com")).thenReturn(Optional.of(mockMember));
        when(passwordEncoder.matches("wrongpass", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("test@example.com", "wrongpass"), mock(HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    // ===== refresh =====

    @Test
    void refresh_blacklisted_throwsTokenRevoked() {
        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.parseAndValidate(anyString())).thenReturn(claims);
        when(jwtTokenProvider.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("1");
        when(refreshTokenService.isBlacklisted(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.refresh(
                new RefreshRequest("blacklisted-token"), mock(HttpServletRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_REVOKED));
    }

    @Test
    void refresh_success_reissuesTokens() {
        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.parseAndValidate(anyString())).thenReturn(claims);
        when(jwtTokenProvider.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("1");
        when(refreshTokenService.isBlacklisted(1L)).thenReturn(false);
        when(refreshTokenService.rotate(anyString())).thenReturn(42L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(mockMember));
        when(jwtTokenProvider.issueRefreshToken(1L)).thenReturn("new-refresh");
        when(jwtTokenProvider.issueAccessToken(any(), any(), any())).thenReturn("new-access");

        LoginResponse response = authService.refresh(
                new RefreshRequest("old-refresh-token"), mock(HttpServletRequest.class));

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refresh_nonMember_reissuesTokens() {
        // 비회원도 refresh 토큰을 발급받으므로 NonMember에서 정보를 해소해야 한다 (F-17)
        NonMember nonMember = NonMember.builder()
                .name("게스트")
                .phone("01099998888")
                .password("hashed")
                .accessCode("ABC1234567")
                .build();
        Claims claims = mock(Claims.class);
        when(jwtTokenProvider.parseAndValidate(anyString())).thenReturn(claims);
        when(jwtTokenProvider.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("7");
        when(refreshTokenService.isBlacklisted(7L)).thenReturn(false);
        when(refreshTokenService.rotate(anyString())).thenReturn(99L);
        when(memberRepository.findById(7L)).thenReturn(Optional.empty());
        when(nonMemberRepository.findById(7L)).thenReturn(Optional.of(nonMember));
        when(jwtTokenProvider.issueRefreshToken(7L)).thenReturn("nm-refresh");
        when(jwtTokenProvider.issueAccessToken(any(), any(), any())).thenReturn("nm-access");

        LoginResponse response = authService.refresh(
                new RefreshRequest("old-refresh-token"), mock(HttpServletRequest.class));

        assertThat(response.accessToken()).isEqualTo("nm-access");
        assertThat(response.refreshToken()).isEqualTo("nm-refresh");
        assertThat(response.role()).isEqualTo(UserRole.ROLE_NON_MEMBER.name());
    }

    // ===== logout =====

    @Test
    void logout_revokesAllTokens() {
        authService.logout(1L);
        verify(refreshTokenService).revokeAll(1L);
    }
}
