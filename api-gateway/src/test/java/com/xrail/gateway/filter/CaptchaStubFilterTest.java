package com.xrail.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaptchaStubFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private CaptchaStubFilter filter;

    @BeforeEach
    void setUp() {
        // lenient: opsForValue() is only needed for tests that reach the Redis check
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        filter = new CaptchaStubFilter(redisTemplate);
    }

    @Test
    void nonCaptchaPath_passesThrough() {
        MockServerWebExchange exchange = exchange("/api/trains", null);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void missingToken_returns403() {
        MockServerWebExchange exchange = exchange("/api/auth/signup", null);
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidBase64Token_returns403() {
        MockServerWebExchange exchange = exchange("/api/auth/signup", "not-valid-base64!!!");
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredToken_returns403() {
        long oldTimestamp = System.currentTimeMillis() - 31_000; // 31s ago
        String token = Base64.getEncoder().encodeToString(String.valueOf(oldTimestamp).getBytes(StandardCharsets.UTF_8));

        MockServerWebExchange exchange = exchange("/api/auth/signup", token);
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void validFreshToken_firstUse_passesThrough() {
        String token = freshToken();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true)); // new token

        MockServerWebExchange exchange = exchange("/api/auth/signup", token);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void validFreshToken_secondUse_returns403() {
        String token = freshToken();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(false)); // token already used

        MockServerWebExchange exchange = exchange("/api/auth/signup", token);
        GatewayFilterChain chain = e -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void queueTokenPath_appliesCaptcha() {
        String token = freshToken();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = exchange("/api/queue/token", token);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void redisFailure_failOpen() {
        String token = freshToken();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new RuntimeException("Redis unavailable")));

        MockServerWebExchange exchange = exchange("/api/auth/signup", token);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = e -> { chainCalled.set(true); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    private MockServerWebExchange exchange(String path, String captchaToken) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post(path);
        if (captchaToken != null) {
            builder.header("X-Captcha-Token", captchaToken);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private String freshToken() {
        long now = System.currentTimeMillis();
        return Base64.getEncoder().encodeToString(String.valueOf(now).getBytes(StandardCharsets.UTF_8));
    }
}
