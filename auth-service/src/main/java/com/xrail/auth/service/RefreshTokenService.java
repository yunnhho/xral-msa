package com.xrail.auth.service;

import com.xrail.auth.entity.RefreshToken;
import com.xrail.auth.repository.RefreshTokenRepository;
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

    @Transactional
    public RefreshToken save(Long userId, String rawToken, String ip, String userAgent, Long rotatedFrom) {
        String hash = sha256(rawToken);
        RefreshToken rt = RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusDays(14))
                .rotatedFrom(rotatedFrom)
                .ipAddress(ip)
                .userAgent(userAgent)
                .build();
        refreshTokenRepository.save(rt);
        mirrorToRedis(userId, hash);
        return rt;
    }

    @Transactional
    public Long rotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken old = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (old.isRevoked() || old.isExpired()) {
            refreshTokenRepository.revokeAllByUserId(old.getUserId(), LocalDateTime.now());
            clearRedis(old.getUserId());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        old.revoke();
        return old.getId();
    }

    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        clearRedis(userId);
    }

    private RefreshToken findValid(String hash) {
        return refreshTokenRepository.findByTokenHash(hash)
                .filter(rt -> !rt.isExpired())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private void mirrorToRedis(Long userId, String hash) {
        RBucket<String> bucket = redissonClient.getBucket("rt:" + userId);
        bucket.set(hash, 14, TimeUnit.DAYS);
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
