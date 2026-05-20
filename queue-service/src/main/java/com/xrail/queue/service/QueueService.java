package com.xrail.queue.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String WAITING_KEY_PREFIX = "queue:waiting:";
    private static final String ACTIVE_KEY_PREFIX  = "queue:active:";
    private static final String SCOPES_KEY         = "queue:scopes";
    private static final String IDEM_KEY_PREFIX    = "queue:idem:";

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    private final RedissonClient redissonClient;
    private final QueueTokenService tokenService;
    private final MeterRegistry meterRegistry;

    /**
     * 대기열 진입. idempotencyKey로 중복 차단.
     * @return 등록된 rank (1-based), 이미 active이면 -1
     */
    public EnterResult enter(Long userId, String scope, String idempotencyKey) {
        // 이미 active 상태이면 즉시 토큰 반환
        String activeKey = ACTIVE_KEY_PREFIX + scope + ":" + userId;
        String existingToken = (String) redissonClient.getBucket(activeKey).get();
        if (existingToken != null) {
            long ttlMs = redissonClient.getBucket(activeKey).remainTimeToLive();
            long expiresAt = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : 0);
            return EnterResult.active(existingToken, expiresAt);
        }

        // Idempotency 체크 (중복 진입 방지)
        if (idempotencyKey != null) {
            String idemKey = IDEM_KEY_PREFIX + idempotencyKey;
            boolean isNew = redissonClient.getBucket(idemKey).setIfAbsent(userId.toString(), Duration.ofMinutes(5));
            if (!isNew) {
                // 이미 대기 중인 경우 현재 rank 반환
                return buildWaitingResult(userId, scope);
            }
        }

        // Sorted Set에 추가 (score = 등록 epoch ms)
        double score = Instant.now().toEpochMilli();
        RScoredSortedSet<String> waitingSet = redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope);
        waitingSet.add(score, userId.toString());

        // scope 목록에 추가 (스케줄러 순회용)
        redissonClient.getSet(SCOPES_KEY).add(scope);

        meterRegistry.gauge("xrail.queue.waiting.size", waitingSet, RScoredSortedSet::size);
        return buildWaitingResult(userId, scope);
    }

    public QueueStatus getStatus(Long userId, String scope) {
        String activeKey = ACTIVE_KEY_PREFIX + scope + ":" + userId;
        String token = (String) redissonClient.getBucket(activeKey).get();
        if (token != null) {
            long ttlMs = redissonClient.getBucket(activeKey).remainTimeToLive();
            long expiresAt = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : 0);
            return QueueStatus.active(token, expiresAt);
        }
        return buildWaitingStatus(userId, scope);
    }

    public void leave(Long userId, String scope) {
        redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope).remove(userId.toString());
        redissonClient.getBucket(ACTIVE_KEY_PREFIX + scope + ":" + userId).delete();
    }

    /**
     * 스케줄러가 호출: top-N명 waiting → active 승급.
     * @return 승급된 userId 목록
     */
    public java.util.List<Long> promoteTopN(String scope, int batchSize) {
        RScoredSortedSet<String> waitingSet = redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope);
        java.util.Collection<String> top = waitingSet.valueRange(0, batchSize - 1);

        java.util.List<Long> promoted = new java.util.ArrayList<>();
        for (String userIdStr : top) {
            Long uid = Long.parseLong(userIdStr);
            long issuedAt = System.currentTimeMillis();
            String token = tokenService.generateToken(uid, scope, issuedAt);

            String activeKey = ACTIVE_KEY_PREFIX + scope + ":" + uid;
            redissonClient.getBucket(activeKey).set(token, Duration.ofSeconds(activeTtlSeconds));
            waitingSet.remove(userIdStr);
            promoted.add(uid);
        }

        if (!promoted.isEmpty()) {
            meterRegistry.counter("xrail.queue.promotions.total").increment(promoted.size());
            log.info("Promoted {} users in scope={}", promoted.size(), scope);
        }
        return promoted;
    }

    public java.util.Set<String> getActiveScopes() {
        return redissonClient.<String>getSet(SCOPES_KEY).readAll();
    }

    public int getWaitingRank(Long userId, String scope) {
        Integer rank = redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope).rank(userId.toString());
        return rank == null ? -1 : rank + 1; // 1-based
    }

    public int getWaitingSize(String scope) {
        return redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope).size();
    }

    // ===== helpers =====

    private EnterResult buildWaitingResult(Long userId, String scope) {
        int rank = getWaitingRank(userId, scope);
        int total = getWaitingSize(scope);
        return EnterResult.waiting(rank, total);
    }

    private QueueStatus buildWaitingStatus(Long userId, String scope) {
        int rank = getWaitingRank(userId, scope);
        int total = getWaitingSize(scope);
        return QueueStatus.waiting(rank, total);
    }

    // ===== inner result types =====

    public record EnterResult(String status, int rank, int totalWaiting, String queueToken, long expiresAt) {
        static EnterResult waiting(int rank, int total) {
            return new EnterResult("WAITING", rank, total, null, 0);
        }
        static EnterResult active(String token, long expiresAt) {
            return new EnterResult("ACTIVE", 0, 0, token, expiresAt);
        }
    }

    public record QueueStatus(String status, int rank, int totalWaiting, String queueToken, long expiresAt) {
        static QueueStatus waiting(int rank, int total) {
            return new QueueStatus("WAITING", rank, total, null, 0);
        }
        static QueueStatus active(String token, long expiresAt) {
            return new QueueStatus("ACTIVE", 0, 0, token, expiresAt);
        }
    }
}
