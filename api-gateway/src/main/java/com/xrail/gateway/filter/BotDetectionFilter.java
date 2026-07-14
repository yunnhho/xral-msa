package com.xrail.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Bot User-Agent detection (S5-B).
 * Adds X-Suspicious: 1 to request headers for known bot UA patterns or missing UA.
 * RateLimitFilter uses this header to halve the allowed capacity.
 * /actuator/health is exempt (health check clients often omit User-Agent).
 */
@Slf4j
@Component
public class BotDetectionFilter implements GlobalFilter, Ordered {

    private static final List<String> BOT_PATTERNS = List.of(
            "python-requests", "curl/", "go-http-client", "apache-httpclient",
            "scrapy", "mechanize", "wget/", "libwww-perl"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if ("/actuator/health".equals(path)) {
            return chain.filter(exchange);
        }

        String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
        if (!isSuspicious(userAgent)) {
            return chain.filter(exchange);
        }

        log.debug("Suspicious User-Agent detected ua='{}' path={}", userAgent, path);
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-Suspicious", "1")
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isSuspicious(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return true;
        String ua = userAgent.toLowerCase();
        return BOT_PATTERNS.stream().anyMatch(ua::contains);
    }

    @Override
    public int getOrder() {
        return -55;
    }
}
