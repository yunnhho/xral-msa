package com.xrail.gateway.filter;

import lombok.extern.slf4j.Slf4j;
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
 * Key: rate:{ip}:{minute_epoch}  TTL: 60s
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

    private final ReactiveStringRedisTemplate redisTemplate;

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip = resolveIp(exchange);
        String path = exchange.getRequest().getPath().value();
        long capacity = path.startsWith("/api/reservations") ? 30 : 100;
        long minuteWindow = System.currentTimeMillis() / 60_000;
        String key = "rate:" + ip + ":" + minuteWindow;

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
                    return applyLocalRateLimit(exchange, chain, ip, minuteWindow, capacity, path);
                });
    }

    private Mono<Void> applyLocalRateLimit(ServerWebExchange exchange, GatewayFilterChain chain,
                                            String ip, long minuteWindow, long capacity, String path) {
        String localKey = ip + ":" + minuteWindow;

        // 분이 바뀌면 오래된 키가 자동으로 유효하지 않아짐. 맵이 너무 커지면 전체 초기화.
        if (localCounts.size() > 5_000) {
            localCounts.clear();
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
