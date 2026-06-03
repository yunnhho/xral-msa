package com.xrail.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Login brute force protection (S5-A).
 * Block key: bruteforce:block:{ip}:{pathKey} TTL 300s
 * Fail counter: bruteforce:fail:{ip}:{pathKey}:{minuteEpoch} TTL 60s
 * 5 failures in 1 minute → 5-minute IP block → 429 + Retry-After: 300
 */
@Slf4j
@Component
public class BruteForceFilter implements GlobalFilter, Ordered {

    private static final List<String> LOGIN_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/non-member/login"
    );
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final int MAX_FAILURES = 5;
    private static final int BLOCK_SECONDS = 300;

    // Atomically increments the per-minute counter and sets the block key when threshold is reached.
    private static final RedisScript<Long> INCR_AND_BLOCK_SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "if c >= tonumber(ARGV[2]) then\n" +
            "  redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])\n" +
            "end\n" +
            "return c",
            Long.class
    );

    private final ReactiveStringRedisTemplate redisTemplate;

    public BruteForceFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean isLoginPath = LOGIN_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
        if (!isLoginPath) {
            return chain.filter(exchange);
        }

        String ip = resolveIp(exchange);
        String pathKey = path.replaceAll("[^a-zA-Z0-9]", "_");
        String blockKey = "bruteforce:block:" + ip + ":" + pathKey;

        return redisTemplate.hasKey(blockKey)
                .defaultIfEmpty(false)
                .flatMap(blocked -> {
                    if (Boolean.TRUE.equals(blocked)) {
                        log.warn("Brute force block applied ip={} path={}", ip, path);
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(BLOCK_SECONDS));
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange)
                            .then(Mono.defer(() -> recordFailureIfNeeded(exchange, ip, pathKey, blockKey)));
                })
                .onErrorResume(e -> {
                    log.warn("BruteForce Redis unavailable ({}), skipping check", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> recordFailureIfNeeded(ServerWebExchange exchange, String ip,
                                              String pathKey, String blockKey) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status == null || (status.value() != 401 && status.value() != 403)) {
            return Mono.empty();
        }
        long minuteWindow = System.currentTimeMillis() / 60_000;
        String countKey = "bruteforce:fail:" + ip + ":" + pathKey + ":" + minuteWindow;
        return redisTemplate.execute(
                        INCR_AND_BLOCK_SCRIPT,
                        List.of(countKey, blockKey),
                        List.of("60", String.valueOf(MAX_FAILURES), String.valueOf(BLOCK_SECONDS))
                ).next()
                .doOnNext(count -> {
                    if (count >= MAX_FAILURES) {
                        log.warn("Brute force threshold reached, IP blocked ip={} pathKey={}", ip, pathKey);
                    }
                })
                .then();
    }

    private String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return -60;
    }
}
