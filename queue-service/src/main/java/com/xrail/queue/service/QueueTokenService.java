package com.xrail.queue.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
public class QueueTokenService {

    @Value("${queue.hmac-secret}")
    private String hmacSecret;

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    /**
     * 큐 토큰 생성: HMAC-SHA256(userId:scope:issuedAt)
     */
    public String generateToken(Long userId, String scope, long issuedAtEpochMs) {
        String payload = userId + ":" + scope + ":" + issuedAtEpochMs;
        return hmacSha256(payload) + "." + issuedAtEpochMs;
    }

    /**
     * 큐 토큰 검증. 유효하면 true.
     */
    public boolean validateToken(Long userId, String scope, String token) {
        if (token == null || !token.contains(".")) return false;
        int lastDot = token.lastIndexOf('.');
        String issuedAtStr = token.substring(lastDot + 1);
        String hmacPart = token.substring(0, lastDot);

        long issuedAt;
        try {
            issuedAt = Long.parseLong(issuedAtStr);
        } catch (NumberFormatException e) {
            return false;
        }

        // TTL 검증
        long nowMs = System.currentTimeMillis();
        if (nowMs - issuedAt > (long) activeTtlSeconds * 1000) return false;

        String expected = hmacSha256(userId + ":" + scope + ":" + issuedAt);
        return expected.equals(hmacPart);
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
