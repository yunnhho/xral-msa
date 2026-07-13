package com.xrail.queue.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 입장 제어(C: 하이브리드) 의사결정 검증 — 평시 우회/임계치 대기/운영자 override/재발급.
 * 즉시입장의 원자적 용량 체크(§4.4)는 Lua(RScript)로 실행되므로 여기서는 RScript.eval 결과를
 * mocking해 분기 로직만 검증한다. Lua 스크립트 자체의 정확성(원자성, 만료분 정리 등)은
 * QueueServiceLuaIntegrationTest(실제 Redis)에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final long USER_ID = 42L;
    private static final String SCOPE = "global";
    private static final int MAX_ACTIVE = 100;

    @Mock private RedissonClient redissonClient;
    @Mock private QueueTokenService tokenService;

    @Mock private RBucket<String> activeUserBucket;
    @Mock private RBucket<Object> modeBucket;
    @Mock private RBucket<String> firstKeyBucket;
    @Mock private RScoredSortedSet<String> waitingSet;
    @Mock private RScoredSortedSet<String> activeSet;
    @Mock private RScript script;

    private QueueService service;

    @BeforeEach
    void setUp() {
        service = new QueueService(redissonClient, tokenService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "activeTtlSeconds", 600);
        ReflectionTestUtils.setField(service, "maxActive", MAX_ACTIVE);
        ReflectionTestUtils.setField(service, "sessionCapSeconds", 600);
        ReflectionTestUtils.setField(service, "releaseSkewMarginMs", 2000L);
        ReflectionTestUtils.setField(service, "batchSize", 100);

        lenient().when(redissonClient.getBucket("queue:active:" + SCOPE + ":" + USER_ID, StringCodec.INSTANCE))
                .thenReturn((RBucket) activeUserBucket);
        lenient().when(redissonClient.getBucket("queue:mode")).thenReturn(modeBucket);
        lenient().when(redissonClient.getBucket("queue:active:first:" + SCOPE + ":" + USER_ID, StringCodec.INSTANCE))
                .thenReturn((RBucket) firstKeyBucket);
        lenient().when(redissonClient.getScoredSortedSet("queue:waiting:" + SCOPE, StringCodec.INSTANCE))
                .thenReturn((RScoredSortedSet) waitingSet);
        lenient().when(redissonClient.getScoredSortedSet("queue:active:set:" + SCOPE, StringCodec.INSTANCE))
                .thenReturn((RScoredSortedSet) activeSet);
        lenient().when(redissonClient.getSet("queue:scopes")).thenReturn(mock(org.redisson.api.RSet.class));
        lenient().when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);

        // 기본: 미접속(active 아님)
        lenient().when(activeUserBucket.isExists()).thenReturn(false);
        lenient().when(activeSet.removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(0);
        lenient().when(tokenService.generateToken(eq(USER_ID), eq(SCOPE), anyLong())).thenReturn("hmac.123");
    }

    @Test
    void auto_belowThreshold_admitsImmediately() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(0);
        // Lua acquire: 자리 있음 → 1
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any(), any())).thenReturn(1L);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.queueToken()).isEqualTo("hmac.123");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(waitingSet, never()).addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void auto_atThreshold_enqueuesWaiting() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(0).thenReturn(1); // 임계치 체크 시 0, 그러나 active가 가득 참
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(0);
        // Lua acquire: 자리 없음(FULL) → 0
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any(), any())).thenReturn(0L);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(activeUserBucket, never()).set(any(), any());
    }

    @Test
    void auto_waitingNotEmpty_enqueuesForFairness() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(3); // 이미 대기자 있음 → 새치기 방지
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(3);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void forceOn_alwaysEnqueues_evenWhenIdle() {
        setMode(QueueService.MODE_FORCE_ON);
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(0);
        lenient().when(waitingSet.size()).thenReturn(0);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(activeUserBucket, never()).set(any(), any());
    }

    @Test
    void forceOff_admitsImmediately_evenWhenOverThreshold() {
        setMode(QueueService.MODE_FORCE_OFF);
        // FORCE_OFF는 운영자 override — 용량 체크(Lua) 없이 직접 ZADD(표 5.9)

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(activeSet).add(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(waitingSet, never()).addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void alreadyActive_withinCap_reissuesFreshToken() {
        // 기존 토큰 재반환 금지 — 소비된 토큰이 재반환되면 예약이 401(already used)로 거부된다
        when(activeUserBucket.isExists()).thenReturn(true);
        when(firstKeyBucket.get()).thenReturn(String.valueOf(System.currentTimeMillis() - 10_000));

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.queueToken()).isEqualTo("hmac.123");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(activeSet).add(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(modeBucket, never()).get();
        // cap 이내 재발급은 first-키를 건드리지 않는다(§4.6)
        verify(firstKeyBucket, never()).set(any(), any());
    }

    @Test
    void alreadyActive_beyondCap_returnsExistingTokenWithoutExtension() {
        // §4.6: cap 초과 재발급은 연장 없이 기존 토큰 그대로 반환
        when(activeUserBucket.isExists()).thenReturn(true);
        long firstIssuedAt = System.currentTimeMillis() - 700_000; // 700s 전 (cap=600s 초과)
        when(firstKeyBucket.get()).thenReturn(String.valueOf(firstIssuedAt));
        when(activeUserBucket.get()).thenReturn("existing-hmac.123");
        when(activeUserBucket.remainTimeToLive()).thenReturn(30_000L);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.queueToken()).isEqualTo("existing-hmac.123");
        verify(activeUserBucket, never()).set(any(), any());
        verify(activeSet, never()).add(anyDouble(), any());
    }

    @Test
    void alreadyActive_missingFirstKey_treatedAsNewSession() {
        // first-키가 없는 비정상 잔존 상태(§4.6) → 새 세션으로 간주해 SET하고 재발급
        when(activeUserBucket.isExists()).thenReturn(true);
        when(firstKeyBucket.get()).thenReturn(null);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(firstKeyBucket).set(any(), any());
    }

    @Test
    void reenter_whileWaiting_doesNotResetRank() {
        // Q6: 대기 중 새로고침(새 Idempotency-Key) 재진입 시 score 갱신 금지 — 순번이 뒤로 밀리면 안 된다
        setMode(QueueService.MODE_FORCE_ON);
        when(waitingSet.addIfAbsent(anyDouble(), eq(String.valueOf(USER_ID)))).thenReturn(false); // 이미 대기 중
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(3);
        when(waitingSet.size()).thenReturn(6);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.rank()).isEqualTo(4);
        verify(waitingSet, never()).add(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void enter_disallowedScope_throwsBusinessException() {
        assertThatThrownBy(() -> service.enter(USER_ID, "schedule:1", null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_QUEUE_SCOPE);
    }

    private void setMode(String mode) {
        when(modeBucket.get()).thenReturn(mode);
    }
}
