package com.xrail.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotDetectionFilterTest {

    private BotDetectionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BotDetectionFilter();
    }

    @Test
    void normalBrowserUA_passesWithoutSuspiciousHeader() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/trains")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isNull();
    }

    @Test
    void pythonRequestsUA_markedSuspicious() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/trains")
                        .header("User-Agent", "python-requests/2.28.0")
                        .build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isEqualTo("1");
    }

    @Test
    void curlUA_markedSuspicious() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/trains")
                        .header("User-Agent", "curl/7.81.0")
                        .build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isEqualTo("1");
    }

    @Test
    void missingUA_markedSuspicious() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/trains").build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isEqualTo("1");
    }

    @Test
    void actuatorHealth_exemptFromBotCheck() {
        // /actuator/health has no UA — should still pass without X-Suspicious
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/actuator/health").build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isNull();
    }

    @Test
    void goHttpClientUA_markedSuspicious() {
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.get("/api/trains")
                        .header("User-Agent", "Go-http-client/1.1")
                        .build());
        AtomicReference<ServerWebExchange> chainExchange = new AtomicReference<>();
        GatewayFilterChain chain = e -> { chainExchange.set(e); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Suspicious")).isEqualTo("1");
    }

    @Test
    void orderIs_minus55() {
        assertThat(filter.getOrder()).isEqualTo(-55);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }
}
