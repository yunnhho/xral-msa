package com.xrail.auth.service;

import com.xrail.auth.entity.RefreshToken;
import com.xrail.auth.repository.RefreshTokenRepository;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private RedissonClient redissonClient;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @SuppressWarnings("unchecked")
    private RBucket<String> mockBucket() {
        return (RBucket<String>) mock(RBucket.class);
    }

    // ===== revokeAll =====

    @Test
    void revokeAll_setsBlacklistKey() {
        long ttlMs = 3_600_000L;
        when(jwtTokenProvider.getRefreshTtlMs()).thenReturn(ttlMs);
        RBucket<Object> blacklistBucket = mock(RBucket.class);
        RBucket<Object> redisBucket = mock(RBucket.class);
        when(redissonClient.getBucket("blacklist:rt:1")).thenReturn(blacklistBucket);
        when(redissonClient.getBucket("rt:1")).thenReturn(redisBucket);

        refreshTokenService.revokeAll(1L);

        verify(refreshTokenRepository).revokeAllByUserId(eq(1L), any(LocalDateTime.class));
        verify(blacklistBucket).set("1", ttlMs, TimeUnit.MILLISECONDS);
    }

    // ===== isBlacklisted =====

    @Test
    void isBlacklisted_keyExists_returnsTrue() {
        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("blacklist:rt:1")).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        assertThat(refreshTokenService.isBlacklisted(1L)).isTrue();
    }

    @Test
    void isBlacklisted_keyAbsent_returnsFalse() {
        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("blacklist:rt:1")).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false);

        assertThat(refreshTokenService.isBlacklisted(1L)).isFalse();
    }

    // ===== rotate =====

    @Test
    void rotate_validToken_revokesOldAndReturnsId() {
        RefreshToken token = RefreshToken.builder()
                .userId(1L)
                .tokenHash("dummy")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();

        String rawToken = "raw-token";
        String hash = RefreshTokenService.sha256(rawToken);
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));

        Long oldId = refreshTokenService.rotate(rawToken);

        assertThat(oldId).isNull(); // builder-created token has no id
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void rotate_revokedToken_revokesAllAndThrows() {
        RefreshToken revokedToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("dummy")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        revokedToken.revoke(); // already revoked

        String rawToken = "already-revoked";
        String hash = RefreshTokenService.sha256(rawToken);
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revokedToken));

        RBucket<Object> redisBucket = mock(RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(redisBucket);

        assertThatThrownBy(() -> refreshTokenService.rotate(rawToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_REVOKED));

        verify(refreshTokenRepository).revokeAllByUserId(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void rotate_notFound_throwsRefreshTokenInvalid() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("unknown-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    // ===== sha256 (utility) =====

    @Test
    void sha256_deterministicHash() {
        String h1 = RefreshTokenService.sha256("hello");
        String h2 = RefreshTokenService.sha256("hello");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
    }

    @Test
    void sha256_differentInputs_differentHash() {
        assertThat(RefreshTokenService.sha256("abc"))
                .isNotEqualTo(RefreshTokenService.sha256("def"));
    }
}
