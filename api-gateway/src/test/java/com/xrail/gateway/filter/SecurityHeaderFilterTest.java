package com.xrail.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeaderFilterTest {

    private final SecurityHeaderFilter filter = new SecurityHeaderFilter();

    @Test
    void allSecurityHeaders_setOnCommit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trains").build());
        GatewayFilterChain chain = e -> Mono.empty();

        // Register beforeCommit callback via filter, then commit
        StepVerifier.create(
                filter.filter(exchange, chain)
                      .then(exchange.getResponse().setComplete())
        ).verifyComplete();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-XSS-Protection")).isEqualTo("1; mode=block");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("Permissions-Policy")).isEqualTo("geolocation=(), microphone=()");
    }

    @Test
    void orderIs_minus90() {
        assertThat(filter.getOrder()).isEqualTo(-90);
    }
}
