package com.xrail.train.interceptor;

import com.xrail.common.header.Headers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
public class QueueTokenInterceptor implements HandlerInterceptor {

    @Value("${queue.hmac-secret}")
    private String hmacSecret;

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!HttpMethod.POST.matches(request.getMethod())) return true;

        String token = request.getHeader(Headers.QUEUE_TOKEN);
        String userIdHeader = request.getHeader(Headers.USER_ID);

        if (token == null || userIdHeader == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing queue token");
            return false;
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid user id");
            return false;
        }

        String scope = request.getParameter("scope");
        if (scope == null) scope = "reservation";

        if (!validateToken(userId, scope, token)) {
            log.warn("Invalid queue token userId={} scope={}", userId, scope);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired queue token");
            return false;
        }
        return true;
    }

    private boolean validateToken(Long userId, String scope, String token) {
        if (!token.contains(".")) return false;
        int lastDot = token.lastIndexOf('.');
        String issuedAtStr = token.substring(lastDot + 1);
        String hmacPart = token.substring(0, lastDot);

        long issuedAt;
        try {
            issuedAt = Long.parseLong(issuedAtStr);
        } catch (NumberFormatException e) {
            return false;
        }

        if (System.currentTimeMillis() - issuedAt > (long) activeTtlSeconds * 1000) return false;

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
