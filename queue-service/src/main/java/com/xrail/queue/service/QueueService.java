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
    private static final String ACTIVE_SET_PREFIX  = "queue:active:set:";
    private static final String SCOPES_KEY         = "queue:scopes";
    private static final String IDEM_KEY_PREFIX    = "queue:idem:";
    private static final String MODE_KEY           = "queue:mode";

    public static final String MODE_AUTO      = "AUTO";       // 평시 우회 + 임계치 초과 시 자동 대기
    public static final String MODE_FORCE_ON  = "FORCE_ON";   // 운영자 강제 ON: 항상 대기열
    public static final String MODE_FORCE_OFF = "FORCE_OFF";  // 운영자 강제 OFF: 대기열 비활성(즉시 입장)

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    @Value("${queue.admission.max-active:100}")
    private int maxActive;

    private final RedissonClient redissonClient;
    private final QueueTokenService tokenService;
    private final MeterRegistry meterRegistry;

    /**
     * 대기열 진입. idempotencyKey로 중복 차단.
     * @return 등록된 rank (1-based), 이미 active이면 -1
     */
    public EnterResult enter(Long userId, String scope, String idempotencyKey) {
        // 이미 active(버킷 보유)면 새 토큰으로 재발급해 즉시 입장시킨다.
        // 기존 토큰을 그대로 재반환하면 직전 예약에서 소비된 토큰이 버킷 TTL(600s) 동안
        // 되살아나 예약 생성이 401(already used)로 거부된다(2026-06-11 부하 테스트에서 발견).
        // 슬롯은 이미 점유 중이므로 재발급은 새치기가 아니다.
        String activeKey = ACTIVE_KEY_PREFIX + scope + ":" + userId;
        if (redissonClient.getBucket(activeKey).isExists()) {
            return admit(userId, scope);
        }

        // 입장 제어(C): FORCE_OFF면 즉시 입장, AUTO면 대기자 없고 active 여유 있을 때만 즉시 입장.
        // FORCE_ON이거나 임계치 초과면 대기열 등록(아래 로직).
        String mode = getMode();
        if (MODE_FORCE_OFF.equals(mode)
                || (MODE_AUTO.equals(mode) && getWaitingSize(scope) == 0 && getActiveCount(scope) < maxActive)) {
            return admit(userId, scope);
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

        // scope 태그 없이 등록하면 모든 scope가 한 게이지로 합쳐져 최초 등록된 scope의 크기만 보인다.
        meterRegistry.gauge("xrail.queue.waiting.size", io.micrometer.core.instrument.Tags.of("scope", scope),
                waitingSet, RScoredSortedSet::size);
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
        redissonClient.getScoredSortedSet(ACTIVE_SET_PREFIX + scope).remove(userId.toString());
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

            long expiresAt = issuedAt + (long) activeTtlSeconds * 1000;
            String activeKey = ACTIVE_KEY_PREFIX + scope + ":" + uid;
            redissonClient.getBucket(activeKey).set(token, Duration.ofSeconds(activeTtlSeconds));
            redissonClient.getScoredSortedSet(ACTIVE_SET_PREFIX + scope).add(expiresAt, userIdStr);
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

    /**
     * 현재 동시 active(입장 허용) 인원. 만료된 항목은 조회 시 정리(score = 만료 epoch ms).
     */
    public int getActiveCount(String scope) {
        RScoredSortedSet<String> activeSet = redissonClient.getScoredSortedSet(ACTIVE_SET_PREFIX + scope);
        activeSet.removeRangeByScore(0, true, System.currentTimeMillis(), true);
        return activeSet.size();
    }

    // ===== 입장 제어 모드 (운영자 override) =====

    public String getMode() {
        String mode = (String) redissonClient.getBucket(MODE_KEY).get();
        return mode == null ? MODE_AUTO : mode;
    }

    /** 허용 값: AUTO / FORCE_ON / FORCE_OFF. 그 외는 컨트롤러 단 @Pattern으로 차단. */
    public void setMode(String mode) {
        redissonClient.getBucket(MODE_KEY).set(mode);
        log.info("Queue admission mode changed to {}", mode);
    }

    /** 운영자 콘솔용 스냅샷: 현재 모드 + 임계치 + scope별 대기/active 현황. */
    public java.util.Map<String, Object> modeSnapshot() {
        java.util.Set<String> scopes = new java.util.TreeSet<>(getActiveScopes());
        scopes.add("global");
        java.util.List<java.util.Map<String, Object>> scopeStats = new java.util.ArrayList<>();
        for (String scope : scopes) {
            scopeStats.add(java.util.Map.of(
                    "scope", scope,
                    "waiting", getWaitingSize(scope),
                    "active", getActiveCount(scope)
            ));
        }
        return java.util.Map.of(
                "mode", getMode(),
                "maxActive", maxActive,
                "scopes", scopeStats
        );
    }

    // ===== helpers =====

    /**
     * 즉시 입장 처리: 큐 토큰 발급 + active 등록(+ active-set 카운팅용 등록). waiting에 있었다면 제거.
     */
    private EnterResult admit(Long userId, String scope) {
        long issuedAt = System.currentTimeMillis();
        String token = tokenService.generateToken(userId, scope, issuedAt);
        long expiresAt = issuedAt + (long) activeTtlSeconds * 1000;

        redissonClient.getBucket(ACTIVE_KEY_PREFIX + scope + ":" + userId)
                .set(token, Duration.ofSeconds(activeTtlSeconds));
        redissonClient.getScoredSortedSet(ACTIVE_SET_PREFIX + scope).add(expiresAt, userId.toString());
        redissonClient.getScoredSortedSet(WAITING_KEY_PREFIX + scope).remove(userId.toString());

        meterRegistry.counter("xrail.queue.immediate.total").increment();
        return EnterResult.active(token, expiresAt);
    }

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
