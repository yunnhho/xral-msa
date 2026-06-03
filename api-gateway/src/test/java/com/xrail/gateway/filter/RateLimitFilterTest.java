package com.xrail.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(redisTemplate);
    }

    @Test
    void loginPath_uses10Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("10");
    }

    @Test
    void signupPath_uses5Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/signup").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("5");
    }

    @Test
    void paymentPath_uses5Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payments").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("5");
    }

    @Test
    void queueTokenPath_uses20Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/queue/token").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("20");
    }

    @Test
    void reservationsPath_uses30Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/reservations").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("30");
    }

    @Test
    void globalPath_uses100Capacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/stations").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("100");
    }

    @Test
    void suspiciousHeader_halvesCapacity() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), argsCaptor.capture()))
                .thenReturn(Flux.just(1L));

        // login (base 10) + suspicious → capacity 5
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-Suspicious", "1")
                        .build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(argsCaptor.getValue().get(0)).isEqualTo("5");
    }

    @Test
    void redisReturns0_responds429() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(0L));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trains").build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void redisFailure_inMemoryFallback_allows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("Redis down")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trains").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void loginAndPayment_useIndependentBuckets() {
        // Verify key contains bucket name, not shared across paths
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        when(redisTemplate.execute(any(RedisScript.class), keyCaptor.capture(), anyList()))
                .thenReturn(Flux.just(1L));

        MockServerWebExchange loginExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build());
        StepVerifier.create(filter.filter(loginExchange, e -> Mono.empty())).verifyComplete();
        String loginKey = keyCaptor.getValue().get(0);

        MockServerWebExchange paymentExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payments").build());
        StepVerifier.create(filter.filter(paymentExchange, e -> Mono.empty())).verifyComplete();
        String paymentKey = keyCaptor.getValue().get(0);

        // Keys must be different (different buckets)
        assertThat(loginKey).isNotEqualTo(paymentKey);
        assertThat(loginKey).contains("auth-login");
        assertThat(paymentKey).contains("payments");
    }
}
