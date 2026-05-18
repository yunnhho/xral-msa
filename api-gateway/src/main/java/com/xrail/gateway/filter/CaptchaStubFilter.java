package com.xrail.gateway.filter;

import com.xrail.common.header.Headers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
public class CaptchaStubFilter implements GlobalFilter, Ordered {

    private static final List<String> CAPTCHA_PATHS = List.of(
            "/api/auth/signup",
            "/api/queue/token"
    );
    private static final String VALID_STUB = "VALID-STUB";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean requiresCaptcha = CAPTCHA_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));

        if (!requiresCaptcha) {
            return chain.filter(exchange);
        }

        String captchaToken = exchange.getRequest().getHeaders().getFirst(Headers.CAPTCHA_TOKEN);
        if (!VALID_STUB.equals(captchaToken)) {
            log.warn("CAPTCHA verification failed for path={}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -75;
    }
}
