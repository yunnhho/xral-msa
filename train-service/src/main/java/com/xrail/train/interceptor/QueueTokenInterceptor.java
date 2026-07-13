package com.xrail.train.interceptor;

import com.xrail.common.header.Headers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * T2 step 1: HMAC 서명 검증 + 만료 체크 + 일회성(S5-D) 토큰 확인.
 * Redis key: queue:token:used:{hmacPart} TTL = activeTtlSeconds (DB 1)
 */
@Slf4j
@Component
public class QueueTokenInterceptor implements HandlerInterceptor {

    @Value("${queue.hmac-secret}")
    private String hmacSecret;

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    private final RedissonClient redissonClient;

    public QueueTokenInterceptor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 보호 대상: 예약 생성(POST /api/reservations, 토큰 일회성 소비) + 좌석 조회(GET .../seats, 검증만).
        // 그 외(예: GET /api/reservations 목록)는 큐 토큰 불필요 → 통과.
        String uri = request.getRequestURI();
        boolean isReservationCreate = HttpMethod.POST.matches(request.getMethod()) && uri.endsWith("/api/reservations");
        boolean isSeatQuery = HttpMethod.GET.matches(request.getMethod()) && uri.endsWith("/seats");
        if (!isReservationCreate && !isSeatQuery) return true;

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
        if (scope == null) scope = "global";

        if (!validateToken(userId, scope, token)) {
            log.warn("Invalid queue token userId={} scope={}", userId, scope);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired queue token");
            return false;
        }

        // S5-D / §4.5: 이미 사용된 토큰 차단(일회성) — POST·GET 공통.
        // 슬롯 반환(reservation.created) 후에도 소비된 토큰으로 좌석 조회가 HMAC 만료까지 통과하면
        // INV-2(슬롯 반환 ⇒ 토큰 사용 불가)가 깨진다 — 좌석 조회도 used 체크를 거친다.
        String hmacPart = token.substring(0, token.lastIndexOf('.'));
        String usedKey = "queue:token:used:" + hmacPart;
        if (redissonClient.getBucket(usedKey).isExists()) {
            log.warn("Queue token already used userId={} scope={}", userId, scope);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Queue token already used");
            return false;
        }

        // 좌석 조회는 검증만 — 토큰을 소비하지 않는다(이어지는 예약 POST에서 1회 소비).
        if (isSeatQuery) return true;

        // 토큰 정보를 afterCompletion에서 사용할 수 있도록 요청에 보관 (POST만 소비)
        request.setAttribute("queueTokenUsedKey", usedKey);
        return true;
    }

    /**
     * S5-D: 예약 생성 성공 시(2xx) 토큰을 사용 완료 처리.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!HttpMethod.POST.matches(request.getMethod())) return;
        String usedKey = (String) request.getAttribute("queueTokenUsedKey");
        if (usedKey == null) return;

        boolean success = ex == null && response.getStatus() >= 200 && response.getStatus() < 300;
        if (success) {
            redissonClient.getBucket(usedKey).set("1", Duration.ofSeconds(activeTtlSeconds));
        }
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
