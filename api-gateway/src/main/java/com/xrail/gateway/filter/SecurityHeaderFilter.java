package com.xrail.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds OWASP-recommended security response headers on every outbound response.
 * Uses beforeCommit to run after downstream headers are merged, so our values
 * always win regardless of what the upstream service sends.
 */
@Component
public class SecurityHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("X-XSS-Protection", "1; mode=block");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.set("Permissions-Policy", "geolocation=(), microphone=()");
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
