package com.xrail.gateway.filter;

import com.xrail.common.header.Headers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Time-based CAPTCHA token validation (S5-C).
 * Token format: base64(timestamp_ms)
 * Valid when: |now - timestamp| <= 30s AND token not seen before.
 * Redis key: captcha:used:{token} TTL 60s — prevents reuse within expiry window.
 * On Redis failure: fail open to avoid blocking legitimate traffic.
 */
@Slf4j
@Component
public class CaptchaStubFilter implements GlobalFilter, Ordered {

    private static final List<String> CAPTCHA_PATHS = List.of(
            "/api/auth/signup",
            "/api/queue/token"
    );
    private static final long MAX_AGE_MS = 30_000L;

    private final ReactiveStringRedisTemplate redisTemplate;

    public CaptchaStubFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!CAPTCHA_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst(Headers.CAPTCHA_TOKEN);
        if (token == null || token.isBlank()) {
            log.warn("CAPTCHA token missing path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        long timestamp;
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            timestamp = Long.parseLong(decoded.trim());
        } catch (Exception e) {
            log.warn("CAPTCHA token decode failed path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        if (Math.abs(System.currentTimeMillis() - timestamp) > MAX_AGE_MS) {
            log.warn("CAPTCHA token expired path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        String usedKey = "captcha:used:" + token;
        return redisTemplate.opsForValue()
                .setIfAbsent(usedKey, "1", Duration.ofSeconds(60))
                .defaultIfEmpty(false)
                .flatMap(isNew -> {
                    if (!isNew) {
                        log.warn("CAPTCHA token reused path={}", path);
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    log.debug("CAPTCHA token valid path={}", path);
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    log.warn("CAPTCHA Redis unavailable ({}), fail open path={}", e.getMessage(), path);
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -75;
    }
}
