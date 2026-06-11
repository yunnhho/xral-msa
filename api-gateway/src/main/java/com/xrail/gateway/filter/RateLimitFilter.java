package com.xrail.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 기반 고정 윈도우 레이트 리미터 (G5).
 * Redis 장애 시 인메모리 버킷으로 graceful degradation — rate limiting이 완전히 해제되지 않는다.
 * Key: rate:{ip}:{bucket}:{minute_epoch}  TTL: 60s
 * 경로별 독립 버킷이므로 login 10/min 소진이 다른 엔드포인트에 영향 없다.
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end\n" +
            "if c > tonumber(ARGV[1]) then return 0 else return 1 end",
            Long.class
    );

    // Redis 장애 시 fallback. 키 = ip:minuteWindow, 값 = 해당 분의 요청 수.
    private final Map<String, AtomicLong> localCounts = new ConcurrentHashMap<>();

    // 부하 테스트(단일 IP에서 수천 요청) 시 false로 비활성화. 운영 기본값 true.
    @Value("${rate-limit.enabled:true}")
    private boolean enabled = true;

    private final ReactiveStringRedisTemplate redisTemplate;

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static String resolveBucket(String path) {
        if (path.equals("/api/auth/login") || path.equals("/api/auth/non-member/login")) return "auth-login";
        if (path.equals("/api/auth/signup") || path.equals("/api/auth/non-member/register")) return "auth-signup";
        if (path.startsWith("/api/payments")) return "payments";
        if (path.equals("/api/queue/token")) return "queue-token";
        if (path.startsWith("/api/reservations")) return "reservations";
        return "global";
    }

    private static long resolveCapacity(String bucket) {
        return switch (bucket) {
            case "auth-login"  -> 10;
            case "auth-signup" -> 5;
            case "payments"    -> 5;
            case "queue-token" -> 20;
            case "reservations"-> 30;
            default            -> 100;
        };
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String ip = resolveIp(exchange);
        String path = exchange.getRequest().getPath().value();
        String bucket = resolveBucket(path);
        long baseCapacity = resolveCapacity(bucket);
        // Halve capacity for bot-flagged requests (S5-B).
        boolean suspicious = "1".equals(exchange.getRequest().getHeaders().getFirst("X-Suspicious"));
        long capacity = suspicious ? Math.max(1, baseCapacity / 2) : baseCapacity;
        long minuteWindow = System.currentTimeMillis() / 60_000;
        String key = "rate:" + ip + ":" + bucket + ":" + minuteWindow;

        return redisTemplate.execute(
                        RATE_LIMIT_SCRIPT,
                        List.of(key),
                        List.of(String.valueOf(capacity), "60")
                )
                .next()
                .defaultIfEmpty(1L)
                .flatMap(result -> {
                    if (result == 0L) {
                        log.warn("Rate limit exceeded ip={} path={}", ip, path);
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().set("Retry-After", "60");
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    // Redis 장애 — 인메모리 fallback으로 레이트 리미팅 유지 (G5)
                    log.warn("Rate limit Redis unavailable ({}), using in-memory fallback", e.getMessage());
                    return applyLocalRateLimit(exchange, chain, ip, bucket, minuteWindow, capacity, path);
                });
    }

    private Mono<Void> applyLocalRateLimit(ServerWebExchange exchange, GatewayFilterChain chain,
                                            String ip, String bucket, long minuteWindow, long capacity, String path) {
        String localKey = ip + ":" + bucket + ":" + minuteWindow;

        // 맵이 커지면 지난 분(window) 키만 정리한다. 전체 clear()는 현재 윈도우 카운터까지
        // 날려 순간적으로 모든 IP의 rate limit이 해제되는 보호 공백을 만들기 때문에 사용하지 않는다.
        if (localCounts.size() > 5_000) {
            String currentSuffix = ":" + minuteWindow;
            localCounts.keySet().removeIf(k -> !k.endsWith(currentSuffix));
        }

        long count = localCounts.computeIfAbsent(localKey, k -> new AtomicLong()).incrementAndGet();
        if (count > capacity) {
            log.warn("Rate limit exceeded (local fallback) ip={} path={}", ip, path);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("Retry-After", "60");
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
