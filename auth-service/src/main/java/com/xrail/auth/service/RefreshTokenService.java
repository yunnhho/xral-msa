package com.xrail.auth.service;

import com.xrail.auth.entity.RefreshToken;
import com.xrail.auth.repository.RefreshTokenRepository;
import com.xrail.auth.security.JwtTokenProvider;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RedissonClient redissonClient;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public RefreshToken save(Long userId, String rawToken, String ip, String userAgent, Long rotatedFrom) {
        String hash = sha256(rawToken);
        long ttlMs = jwtTokenProvider.getRefreshTtlMs();
        RefreshToken rt = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusSeconds(ttlMs / 1000))
                .rotatedFrom(rotatedFrom)
                .ipAddress(ip)
                .userAgent(userAgent)
                .build();
        refreshTokenRepository.save(rt);
        mirrorToRedis(userId, hash);
        // Re-login after logout clears the user-level blacklist
        redissonClient.getBucket("blacklist:rt:" + userId).delete();
        return rt;
    }

    @Transactional
    public Long rotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken old = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (old.isRevoked() || old.isExpired()) {
            refreshTokenRepository.revokeAllByUserId(old.getUserId(), LocalDateTime.now());
            clearRedis(old.getUserId());
            throw new BusinessException(ErrorCode.TOKEN_REVOKED);
        }

        old.revoke();
        return old.getId();
    }

    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        clearRedis(userId);
        long ttlMs = jwtTokenProvider.getRefreshTtlMs();
        redissonClient.getBucket("blacklist:rt:" + userId).set("1", ttlMs, TimeUnit.MILLISECONDS);
    }

    public boolean isBlacklisted(Long userId) {
        return redissonClient.getBucket("blacklist:rt:" + userId).isExists();
    }

    private void mirrorToRedis(Long userId, String hash) {
        long ttlMs = jwtTokenProvider.getRefreshTtlMs();
        RBucket<String> bucket = redissonClient.getBucket("rt:" + userId);
        bucket.set(hash, ttlMs, TimeUnit.MILLISECONDS);
    }

    private void clearRedis(Long userId) {
        redissonClient.getBucket("rt:" + userId).delete();
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
