package com.xrail.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BruteForceFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private BruteForceFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BruteForceFilter(redisTemplate);
    }

    @Test
    void nonLoginPath_skipsCheck() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/reservations").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    void loginPath_blocked_returns429() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("300");
    }

    @Test
    void loginPath_notBlocked_passesThrough() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
        // 401 response → increment counter
        when(redisTemplate.execute(any(), anyList(), anyList())).thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void loginPath_successResponse_noCounterIncrement() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // No execute call for counter increment on success
        verify(redisTemplate, never()).execute(any(), anyList(), anyList());
    }

    @Test
    void nonMemberLoginPath_alsoProtected() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/non-member/login").build());
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void redisUnavailable_failsOpen() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.error(new RuntimeException("Redis down")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // On Redis failure, fail open (allow request)
        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void orderIs_minus60() {
        assertThat(filter.getOrder()).isEqualTo(-60);
    }
}
