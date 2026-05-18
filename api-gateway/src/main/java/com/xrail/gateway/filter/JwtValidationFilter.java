package com.xrail.gateway.filter;

import com.xrail.common.header.Headers;
import com.xrail.gateway.security.JwtVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtValidationFilter implements GlobalFilter, Ordered {

    private static final List<String> PERMIT_PATTERNS = List.of(
            "/api/auth/**",
            "/oauth2/**",
            "/login/oauth2/**",
            "/actuator/**"
    );
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtVerifier jwtVerifier;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isPermitted(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        Claims claims;
        try {
            claims = jwtVerifier.parseAndValidate(token);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String role = claims.get("role", String.class);
        String name = claims.get("name", String.class);
        String userId = claims.getSubject();

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(Headers.USER_ID, userId)
                .header(Headers.USER_ROLE, role != null ? role : "")
                .header(Headers.USER_NAME, name != null ? name : "")
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isPermitted(String path) {
        return PERMIT_PATTERNS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
