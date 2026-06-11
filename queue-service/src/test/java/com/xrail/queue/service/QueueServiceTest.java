package com.xrail.queue.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 입장 제어(C: 하이브리드) 의사결정 검증 — 평시 우회/임계치 대기/운영자 override.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final long USER_ID = 42L;
    private static final String SCOPE = "global";
    private static final int MAX_ACTIVE = 100;

    @Mock private RedissonClient redissonClient;
    @Mock private QueueTokenService tokenService;

    @Mock private RBucket<Object> activeUserBucket;
    @Mock private RBucket<Object> modeBucket;
    @Mock private RScoredSortedSet<String> waitingSet;
    @Mock private RScoredSortedSet<String> activeSet;

    private QueueService service;

    @BeforeEach
    void setUp() {
        service = new QueueService(redissonClient, tokenService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "activeTtlSeconds", 600);
        ReflectionTestUtils.setField(service, "maxActive", MAX_ACTIVE);

        lenient().when(redissonClient.getBucket("queue:active:" + SCOPE + ":" + USER_ID)).thenReturn(activeUserBucket);
        lenient().when(redissonClient.getBucket("queue:mode")).thenReturn(modeBucket);
        lenient().when(redissonClient.<String>getScoredSortedSet("queue:waiting:" + SCOPE)).thenReturn(waitingSet);
        lenient().when(redissonClient.<String>getScoredSortedSet("queue:active:set:" + SCOPE)).thenReturn(activeSet);
        lenient().when(redissonClient.getSet("queue:scopes")).thenReturn(mock(org.redisson.api.RSet.class));

        // 기본: 미접속(active 아님)
        lenient().when(activeUserBucket.get()).thenReturn(null);
        lenient().when(activeSet.removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(0);
        lenient().when(tokenService.generateToken(eq(USER_ID), eq(SCOPE), anyLong())).thenReturn("hmac.123");
    }

    @Test
    void auto_belowThreshold_admitsImmediately() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(0);
        when(activeSet.size()).thenReturn(5); // < 100

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.queueToken()).isEqualTo("hmac.123");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(waitingSet, never()).add(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void auto_atThreshold_enqueuesWaiting() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(0).thenReturn(1); // 임계치 체크 시 0, 그러나 active가 가득 참
        when(activeSet.size()).thenReturn(MAX_ACTIVE);       // >= 100 → 대기
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(0);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).add(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(activeUserBucket, never()).set(any(), any());
    }

    @Test
    void auto_waitingNotEmpty_enqueuesForFairness() {
        setMode(QueueService.MODE_AUTO);
        when(waitingSet.size()).thenReturn(3); // 이미 대기자 있음 → 새치기 방지
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(3);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).add(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void forceOn_alwaysEnqueues_evenWhenIdle() {
        setMode(QueueService.MODE_FORCE_ON);
        when(waitingSet.rank(String.valueOf(USER_ID))).thenReturn(0);
        lenient().when(waitingSet.size()).thenReturn(0);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("WAITING");
        verify(waitingSet).add(anyDouble(), eq(String.valueOf(USER_ID)));
        verify(activeUserBucket, never()).set(any(), any());
    }

    @Test
    void forceOff_admitsImmediately_evenWhenOverThreshold() {
        setMode(QueueService.MODE_FORCE_OFF);
        lenient().when(activeSet.size()).thenReturn(MAX_ACTIVE + 50); // 임계치 초과여도 OFF면 즉시 입장

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(waitingSet, never()).add(anyDouble(), eq(String.valueOf(USER_ID)));
    }

    @Test
    void alreadyActive_reissuesFreshToken() {
        // 기존 토큰 재반환 금지 — 소비된 토큰이 재반환되면 예약이 401(already used)로 거부된다
        when(activeUserBucket.isExists()).thenReturn(true);

        QueueService.EnterResult result = service.enter(USER_ID, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.queueToken()).isEqualTo("hmac.123");
        verify(activeUserBucket).set(eq("hmac.123"), any());
        verify(modeBucket, never()).get();
    }

    private void setMode(String mode) {
        when(modeBucket.get()).thenReturn(mode);
    }
}
