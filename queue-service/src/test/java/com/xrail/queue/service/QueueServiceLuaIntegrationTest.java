package com.xrail.queue.service;

import com.xrail.queue.support.EmbeddedRedisExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * promote.lua / release.lua / 즉시입장 Lua(acquire)는 Redis 원자 실행이 필요해 Mockito로 검증할 수
 * 없다 — QueueServiceTest는 분기 로직을, 이 클래스는 실제 Redis 위에서 Lua 스크립트 자체의 정확성을
 * 검증한다(§7). §4.6 재발급 세션 상한, ABA/skew 가드, 동시성(INV-1)도 여기서 검증한다.
 */
class QueueServiceLuaIntegrationTest {

    private static final String SCOPE = "global";
    private static final String HMAC_SECRET = "test-hmac-secret-32chars-minimum!!";

    @RegisterExtension
    static EmbeddedRedisExtension redis = new EmbeddedRedisExtension();

    private RedissonClient redissonClient;
    private QueueService service;
    private QueueTokenService tokenService;

    @BeforeEach
    void setUp() throws Exception {
        redissonClient = redis.newClient();
        redissonClient.getKeys().flushdb();

        tokenService = new QueueTokenService();
        ReflectionTestUtils.setField(tokenService, "hmacSecret", HMAC_SECRET);
        ReflectionTestUtils.setField(tokenService, "activeTtlSeconds", 600);

        service = new QueueService(redissonClient, tokenService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "activeTtlSeconds", 600);
        ReflectionTestUtils.setField(service, "maxActive", 100);
        ReflectionTestUtils.setField(service, "sessionCapSeconds", 600);
        ReflectionTestUtils.setField(service, "releaseSkewMarginMs", 2000L);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        service.loadScripts();
    }

    @AfterEach
    void tearDown() {
        redissonClient.shutdown();
    }

    // ===== promote.lua =====

    @Test
    void promote_fullActive_promotesZero() {
        ReflectionTestUtils.setField(service, "maxActive", 3);
        seedActive(SCOPE, futureMs(), "901", "902", "903"); // active 가득 참
        seedWaiting(SCOPE, "1", "2");

        List<Long> promoted = service.promoteTopN(SCOPE, 10);

        assertThat(promoted).isEmpty();
        assertThat(waitingSet(SCOPE).size()).isEqualTo(2); // waiting 불변
    }

    @Test
    void promote_partialCapacity_promotesMinOfCapacityAndBatch() {
        ReflectionTestUtils.setField(service, "maxActive", 5);
        seedActive(SCOPE, futureMs(), "901", "902"); // capacity = 5-2 = 3
        seedWaiting(SCOPE, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

        List<Long> promoted = service.promoteTopN(SCOPE, 10); // batchSize=10 > capacity=3

        assertThat(promoted).containsExactly(1L, 2L, 3L); // min(batch=10, capacity=3), 등록 순
        assertThat(waitingSet(SCOPE).size()).isEqualTo(7);
        assertThat(activeSet(SCOPE).size()).isEqualTo(5);
    }

    @Test
    void promote_waitingSmallerThanCapacity_promotesAllWaiting() {
        ReflectionTestUtils.setField(service, "maxActive", 100);
        seedWaiting(SCOPE, "1", "2");

        List<Long> promoted = service.promoteTopN(SCOPE, 10);

        assertThat(promoted).containsExactly(1L, 2L);
        assertThat(waitingSet(SCOPE).size()).isZero();
    }

    // ===== 즉시입장 Lua(acquire, §4.4) =====

    @Test
    void acquire_staleExpiredMembersOnly_purgedThenAdmits() {
        ReflectionTestUtils.setField(service, "maxActive", 1);
        seedActive(SCOPE, System.currentTimeMillis() - 5_000, "999"); // 이미 만료된 잔존 원소

        QueueService.EnterResult result = service.enter(1L, SCOPE, null);

        assertThat(result.status()).isEqualTo("ACTIVE"); // 만료분 정리 후 자리 있음 → FULL 오판 없음
    }

    @Test
    void acquire_reissueAtActiveMax_notRefused() {
        ReflectionTestUtils.setField(service, "maxActive", 1);
        QueueService.EnterResult first = service.enter(1L, SCOPE, null);
        assertThat(first.status()).isEqualTo("ACTIVE"); // 자리 1개 소진

        // 같은 유저 재진입(재발급) — 용량 체크를 타지 않아야 함(§4.4)
        QueueService.EnterResult reissued = service.enter(1L, SCOPE, null);
        assertThat(reissued.status()).isEqualTo("ACTIVE");

        // 다른 유저는 자리 없음 → 대기
        QueueService.EnterResult other = service.enter(2L, SCOPE, null);
        assertThat(other.status()).isEqualTo("WAITING");
    }

    // ===== release.lua =====

    @Test
    void release_idempotent_secondCallIsNoop() {
        QueueService.EnterResult entered = service.enter(1L, SCOPE, null);
        long issuedAt = issuedAtOf(entered.queueToken());
        long occurredAt = issuedAt + 10_000; // skewMargin(2000ms) 밖 → 반환 조건 충족

        boolean first = service.releaseSlot(SCOPE, 1L, occurredAt);
        boolean second = service.releaseSlot(SCOPE, 1L, occurredAt);

        assertThat(first).isTrue();
        assertThat(second).isFalse(); // 버킷 부재 → skip
        assertThat(activeSet(SCOPE).size()).isZero();
    }

    @Test
    void release_bucketAbsent_zsetPreserved() {
        seedActive(SCOPE, futureMs(), "777"); // 버킷 없이 zset 원소만 존재

        boolean released = service.releaseSlot(SCOPE, 777L, System.currentTimeMillis());

        assertThat(released).isFalse();
        assertThat(activeSet(SCOPE).contains("777")).isTrue();
    }

    @Test
    void release_abaSkewMargin_skipsWithinMargin() {
        QueueService.EnterResult entered = service.enter(1L, SCOPE, null);
        long issuedAt = issuedAtOf(entered.queueToken());

        // issuedAt ∈ [occurredAt - margin, occurredAt] → skip
        long occurredAtWithinMargin = issuedAt + 1_000; // margin=2000ms 이내
        boolean releasedWithinMargin = service.releaseSlot(SCOPE, 1L, occurredAtWithinMargin);
        assertThat(releasedWithinMargin).isFalse();
        assertThat(activeSet(SCOPE).contains("1")).isTrue();

        // issuedAt > occurredAt (미래 재발급) → skip
        boolean releasedFuture = service.releaseSlot(SCOPE, 1L, issuedAt - 500);
        assertThat(releasedFuture).isFalse();
        assertThat(activeSet(SCOPE).contains("1")).isTrue();

        // margin 밖 → 정상 반환
        boolean releasedOutsideMargin = service.releaseSlot(SCOPE, 1L, issuedAt + 5_000);
        assertThat(releasedOutsideMargin).isTrue();
        assertThat(activeSet(SCOPE).contains("1")).isFalse();
    }

    @Test
    void release_scheduleScope_worksViaDirectSeed() {
        // enter()는 phase-1에 global만 허용하므로 schedule:{id} scope는 Redis에 직접 시딩한다(표 5.7).
        String scheduleScope = "schedule:5";
        long issuedAt = System.currentTimeMillis() - 20_000;
        String token = tokenService.generateToken(9L, scheduleScope, issuedAt);
        redissonClient.getBucket("queue:active:" + scheduleScope + ":9", StringCodec.INSTANCE)
                .set(token, Duration.ofSeconds(600));
        seedActive(scheduleScope, futureMs(), "9");

        boolean released = service.releaseSlot(scheduleScope, 9L, issuedAt + 10_000);

        assertThat(released).isTrue();
        assertThat(activeSet(scheduleScope).contains("9")).isFalse();
        assertThat(redissonClient.getBucket("queue:active:" + scheduleScope + ":9", StringCodec.INSTANCE).isExists())
                .isFalse();
    }

    // ===== §4.6 재발급 세션 상한 =====

    @Test
    void reissue_withinCap_extendsScoreAndToken() throws InterruptedException {
        QueueService.EnterResult first = service.enter(1L, SCOPE, null);
        Double firstScore = activeSet(SCOPE).getScore("1");

        // 토큰은 issuedAt(ms)에 결정적 — 같은 ms 안에 재발급되면 동일 토큰이라 flaky. 1ms 이상 경과 보장.
        Thread.sleep(2);
        QueueService.EnterResult reissued = service.enter(1L, SCOPE, null);

        assertThat(reissued.status()).isEqualTo("ACTIVE");
        assertThat(reissued.queueToken()).isNotEqualTo(first.queueToken()); // 새 issuedAt → 다른 토큰
        Double newScore = activeSet(SCOPE).getScore("1");
        assertThat(newScore).isGreaterThanOrEqualTo(firstScore); // TTL/score 연장
    }

    @Test
    void reissue_beyondCap_returnsExistingTokenWithoutExtension() {
        QueueService.EnterResult first = service.enter(1L, SCOPE, null);
        Double originalScore = activeSet(SCOPE).getScore("1");

        // first-키를 cap(600s) 초과 시점으로 되돌려 cap 초과 상태를 시뮬레이션
        redissonClient.getBucket("queue:active:first:" + SCOPE + ":1", StringCodec.INSTANCE)
                .set(String.valueOf(System.currentTimeMillis() - 700_000), Duration.ofSeconds(3600));

        QueueService.EnterResult reissued = service.enter(1L, SCOPE, null);

        assertThat(reissued.status()).isEqualTo("ACTIVE");
        assertThat(reissued.queueToken()).isEqualTo(first.queueToken()); // 연장 없이 기존 토큰 그대로
        Double scoreAfter = activeSet(SCOPE).getScore("1");
        assertThat(scoreAfter).isEqualTo(originalScore); // score 갱신 없음
    }

    @Test
    void reenter_afterRelease_startsNewSession() {
        QueueService.EnterResult first = service.enter(1L, SCOPE, null);
        long issuedAt = issuedAtOf(first.queueToken());
        boolean released = service.releaseSlot(SCOPE, 1L, issuedAt + 10_000);
        assertThat(released).isTrue();
        assertThat(redissonClient.getBucket("queue:active:first:" + SCOPE + ":1", StringCodec.INSTANCE).isExists())
                .isFalse(); // 반환 시 first-키도 삭제

        QueueService.EnterResult second = service.enter(1L, SCOPE, null);

        assertThat(second.status()).isEqualTo("ACTIVE");
        assertThat(second.queueToken()).isNotEqualTo(first.queueToken());
        String newFirstIssuedAt = redissonClient.<String>getBucket(
                "queue:active:first:" + SCOPE + ":1", StringCodec.INSTANCE).get();
        assertThat(Long.parseLong(newFirstIssuedAt)).isGreaterThan(issuedAt);
    }

    @Test
    void reenter_withStaleLeftoverFirstKey_overwritesForNewSession() {
        QueueService.EnterResult first = service.enter(1L, SCOPE, null);
        long staleFirstIssuedAt = System.currentTimeMillis() - 900_000; // TTL 만료/leave 후 잔존 가정
        redissonClient.getBucket("queue:active:first:" + SCOPE + ":1", StringCodec.INSTANCE)
                .set(String.valueOf(staleFirstIssuedAt), Duration.ofSeconds(3600));
        // 버킷만 삭제(TTL 만료 시뮬레이션) — first-키는 잔존
        redissonClient.getBucket("queue:active:" + SCOPE + ":1", StringCodec.INSTANCE).delete();

        QueueService.EnterResult second = service.enter(1L, SCOPE, null);

        assertThat(second.status()).isEqualTo("ACTIVE"); // 새 admission 경로(bucket 부재)
        String newFirstIssuedAt = redissonClient.<String>getBucket(
                "queue:active:first:" + SCOPE + ":1", StringCodec.INSTANCE).get();
        assertThat(Long.parseLong(newFirstIssuedAt)).isNotEqualTo(staleFirstIssuedAt); // 덮어쓰기 SET
    }

    // ===== 동시성(INV-1) =====

    @Test
    void concurrentEnter_neverExceedsMaxActive() throws InterruptedException {
        int maxActive = 20;
        int threads = 50;
        ReflectionTestUtils.setField(service, "maxActive", maxActive);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger activeCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            long userId = i + 1;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    QueueService.EnterResult result = service.enter(userId, SCOPE, null);
                    if ("ACTIVE".equals(result.status())) {
                        activeCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).isTrue();
        assertThat(activeCount.get()).isLessThanOrEqualTo(maxActive);
        assertThat(service.getActiveCount(SCOPE)).isLessThanOrEqualTo(maxActive);
        assertThat(service.getActiveCount(SCOPE)).isEqualTo(activeCount.get());
    }

    // ===== E2E: 자리 기반 리필 =====

    @Test
    void e2e_refillOnlyAsMuchAsReleased() {
        // §7: maxActive=5, waiting=20, 승급자 중 3명 예약(반환)/2명 no-show → 다음 tick 3자리만 리필
        ReflectionTestUtils.setField(service, "maxActive", 5);
        String[] uids = new String[20];
        for (int i = 0; i < 20; i++) uids[i] = String.valueOf(i + 1);
        seedWaiting(SCOPE, uids);

        List<Long> firstBatch = service.promoteTopN(SCOPE, 100);
        assertThat(firstBatch).containsExactly(1L, 2L, 3L, 4L, 5L);

        // 3명 예약 완료 → reservation.created 반환 (occurredAt은 skew 마진 밖)
        for (long uid : new long[]{1L, 2L, 3L}) {
            String token = redissonClient.<String>getBucket(
                    "queue:active:" + SCOPE + ":" + uid, StringCodec.INSTANCE).get();
            long issuedAt = issuedAtOf(token);
            assertThat(service.releaseSlot(SCOPE, uid, issuedAt + 10_000)).isTrue();
        }
        // 2명(4,5)은 no-show — 슬롯 계속 점유

        List<Long> secondBatch = service.promoteTopN(SCOPE, 100);
        assertThat(secondBatch).containsExactly(6L, 7L, 8L); // 반환된 3자리만 리필
        assertThat(service.getActiveCount(SCOPE)).isEqualTo(5);
        assertThat(waitingSet(SCOPE).size()).isEqualTo(12);
    }

    // ===== helpers =====

    private void seedWaiting(String scope, String... uids) {
        RScoredSortedSet<String> set = waitingSet(scope);
        double score = 1;
        for (String uid : uids) {
            set.add(score++, uid);
        }
    }

    private void seedActive(String scope, long score, String... uids) {
        RScoredSortedSet<String> set = activeSet(scope);
        for (String uid : uids) {
            set.add(score, uid);
        }
    }

    private RScoredSortedSet<String> waitingSet(String scope) {
        return redissonClient.getScoredSortedSet("queue:waiting:" + scope, StringCodec.INSTANCE);
    }

    private RScoredSortedSet<String> activeSet(String scope) {
        return redissonClient.getScoredSortedSet("queue:active:set:" + scope, StringCodec.INSTANCE);
    }

    private long futureMs() {
        return System.currentTimeMillis() + 600_000;
    }

    private long issuedAtOf(String token) {
        return Long.parseLong(token.substring(token.lastIndexOf('.') + 1));
    }
}
