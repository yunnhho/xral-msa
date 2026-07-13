package com.xrail.train.interceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueTokenInterceptorTest {

    private static final String SECRET = "test-hmac-secret-32chars-minimum!!";
    private static final int ACTIVE_TTL = 600;
    private static final long USER_ID = 42L;

    @Mock
    private RedissonClient redissonClient;

    private QueueTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new QueueTokenInterceptor(redissonClient);
        ReflectionTestUtils.setField(interceptor, "hmacSecret", SECRET);
        ReflectionTestUtils.setField(interceptor, "activeTtlSeconds", ACTIVE_TTL);
    }

    @Test
    void getRequest_skipsValidation() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/reservations");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isTrue();
        verify(redissonClient, never()).getBucket(anyString());
    }

    @Test
    void missingToken_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/reservations");
        req.addHeader("X-User-Id", String.valueOf(USER_ID));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void missingUserId_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/reservations");
        req.addHeader("X-Queue-Token", "some.token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidHmac_returns401() throws Exception {
        long issuedAt = System.currentTimeMillis();
        String fakeToken = "invalid-hmac." + issuedAt;

        MockHttpServletRequest req = postRequest(fakeToken);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void expiredToken_returns401() throws Exception {
        long expiredAt = System.currentTimeMillis() - (ACTIVE_TTL * 1000L + 1000);
        String token = buildToken(USER_ID, "global", expiredAt);

        MockHttpServletRequest req = postRequest(token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void validToken_alreadyUsed_returns401() throws Exception {
        String token = buildToken(USER_ID, "global", System.currentTimeMillis());
        String hmacPart = token.substring(0, token.lastIndexOf('.'));

        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("queue:token:used:" + hmacPart)).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        MockHttpServletRequest req = postRequest(token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void seatQuery_validToken_passesWithoutConsuming() throws Exception {
        String token = buildToken(USER_ID, "global", System.currentTimeMillis());
        String hmacPart = token.substring(0, token.lastIndexOf('.'));

        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("queue:token:used:" + hmacPart)).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/schedules/5/seats");
        req.addHeader("X-Queue-Token", token);
        req.addHeader("X-User-Id", String.valueOf(USER_ID));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        // §4.5: used 체크는 통과(미사용)하지만 좌석 조회는 소비하지 않는다 — attribute 세팅 없음
        assertThat(result).isTrue();
        assertThat(req.getAttribute("queueTokenUsedKey")).isNull();
    }

    @Test
    void seatQuery_usedToken_returns401() throws Exception {
        // §4.5: 슬롯 반환(reservation.created) 후 소비된 토큰으로 좌석 조회 시 INV-2를 지키기 위해 차단
        String token = buildToken(USER_ID, "global", System.currentTimeMillis());
        String hmacPart = token.substring(0, token.lastIndexOf('.'));

        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("queue:token:used:" + hmacPart)).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/schedules/5/seats");
        req.addHeader("X-Queue-Token", token);
        req.addHeader("X-User-Id", String.valueOf(USER_ID));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void seatQuery_missingToken_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/schedules/5/seats");
        req.addHeader("X-User-Id", String.valueOf(USER_ID));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void validToken_firstUse_passesThrough() throws Exception {
        String token = buildToken(USER_ID, "global", System.currentTimeMillis());
        String hmacPart = token.substring(0, token.lastIndexOf('.'));

        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("queue:token:used:" + hmacPart)).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false);

        MockHttpServletRequest req = postRequest(token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, null);

        assertThat(result).isTrue();
        assertThat(req.getAttribute("queueTokenUsedKey")).isEqualTo("queue:token:used:" + hmacPart);
    }

    @Test
    void afterCompletion_successStatus_marksTokenUsed() throws Exception {
        String usedKey = "queue:token:used:some-hmac";
        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket(usedKey)).thenReturn(bucket);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/reservations");
        req.setAttribute("queueTokenUsedKey", usedKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(201);

        interceptor.afterCompletion(req, res, null, null);

        verify(bucket).set(anyString(), any(Duration.class));
    }

    @Test
    void afterCompletion_failStatus_doesNotMarkTokenUsed() throws Exception {
        String usedKey = "queue:token:used:some-hmac";
        RBucket<Object> bucket = mock(RBucket.class);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/reservations");
        req.setAttribute("queueTokenUsedKey", usedKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(500);

        interceptor.afterCompletion(req, res, null, new RuntimeException("error"));

        verify(redissonClient, never()).getBucket(usedKey);
    }

    // ===== helpers =====

    private MockHttpServletRequest postRequest(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/reservations");
        req.addHeader("X-Queue-Token", token);
        req.addHeader("X-User-Id", String.valueOf(USER_ID));
        return req;
    }

    private String buildToken(long userId, String scope, long issuedAt) throws Exception {
        String data = userId + ":" + scope + ":" + issuedAt;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hmac = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        return hmac + "." + issuedAt;
    }
}
