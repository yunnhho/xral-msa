package com.xrail.queue.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String WAITING_KEY_PREFIX      = "queue:waiting:";
    private static final String ACTIVE_KEY_PREFIX        = "queue:active:";
    private static final String ACTIVE_SET_PREFIX        = "queue:active:set:";
    private static final String ACTIVE_FIRST_KEY_PREFIX  = "queue:active:first:";
    private static final String SCOPES_KEY               = "queue:scopes";
    private static final String IDEM_KEY_PREFIX          = "queue:idem:";
    private static final String MODE_KEY                 = "queue:mode";

    public static final String MODE_AUTO      = "AUTO";       // 평시 우회 + 임계치 초과 시 자동 대기
    public static final String MODE_FORCE_ON  = "FORCE_ON";   // 운영자 강제 ON: 항상 대기열
    public static final String MODE_FORCE_OFF = "FORCE_OFF";  // 운영자 강제 OFF: 대기열 비활성(즉시 입장)

    // 표 5.7 ③: phase-1은 scope 확장 전까지 "global"만 허용 — 임의 scope로 상한(maxActive) 우회 차단
    private static final Set<String> ALLOWED_SCOPES = Set.of("global");

    // §4.4: 즉시입장 fast-path 원자적 슬롯 확보. 만료분 정리 후 자리 있으면 ZADD, 없으면 FULL.
    // (checklist상 신규 lua 리소스 파일은 promote.lua/release.lua 2개뿐이라 인라인 스크립트로 둔다.)
    private static final String ACQUIRE_SLOT_SCRIPT =
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) " +
            "local activeCount = redis.call('ZCARD', KEYS[1]) " +
            "if activeCount < tonumber(ARGV[2]) then " +
            "  redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    // §5.2-상세: 최근 60초 반환 이동평균 — 표본 5건 미만이면 콜드스타트 폴백
    private static final long WAIT_ESTIMATE_WINDOW_MS   = 60_000L;
    private static final int  WAIT_ESTIMATE_MIN_SAMPLES = 5;

    @Value("${queue.active-ttl-seconds:600}")
    private int activeTtlSeconds;

    @Value("${queue.admission.max-active:100}")
    private int maxActive;

    @Value("${queue.admission.session-cap-seconds:600}")
    private int sessionCapSeconds;

    @Value("${queue.admission.release-skew-margin-ms:2000}")
    private long releaseSkewMarginMs;

    @Value("${queue.scheduler.batch-size:100}")
    private int batchSize;

    private final RedissonClient redissonClient;
    private final QueueTokenService tokenService;
    private final MeterRegistry meterRegistry;

    private String promoteScript;
    private String releaseScript;

    // §5.2-상세: scope별 최근 반환 시각(ms) — 단일 인스턴스 가정(Q3), in-memory
    private final Map<String, Deque<Long>> recentReleases = new ConcurrentHashMap<>();

    // active.size 게이지 상태 객체 강참조 보관용(약참조 GC로 인한 NaN 방지)
    private final Map<String, String> gaugedScopes = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadScripts() throws IOException {
        promoteScript = loadResource("lua/promote.lua");
        releaseScript = loadResource("lua/release.lua");
        log.info("Lua scripts loaded: promote.lua, release.lua");
    }

    /**
     * 대기열 진입. idempotencyKey로 중복 차단.
     * @return 등록된 rank (1-based), 이미 active이면 -1
     */
    public QueueStatus enter(Long userId, String scope, String idempotencyKey) {
        validateScope(scope);
        registerActiveGauge(scope);

        // 이미 active(버킷 보유)면 재발급 경로(§4.6). 기존 토큰을 그대로 재반환하면 직전 예약에서
        // 소비된 토큰이 버킷 TTL(600s) 동안 되살아나 예약 생성이 401(already used)로 거부된다
        // (2026-06-11 부하 테스트에서 발견). 재발급은 용량 체크를 타지 않는다(§4.4) — 기존 점유자는
        // 카운트를 늘리지 않는다.
        if (redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE).isExists()) {
            return reissue(userId, scope);
        }

        // 입장 제어(C): FORCE_OFF면 즉시 입장(운영자 override — 표 5.9, INV-1 예외로 문서화).
        // AUTO면 대기자가 없을 때만 원자적 슬롯 확보를 시도한다.
        String mode = getMode();
        if (MODE_FORCE_OFF.equals(mode)) {
            return admitBypassingCapacity(userId, scope);
        }
        if (MODE_AUTO.equals(mode) && getWaitingSize(scope) == 0) {
            QueueStatus admitted = tryAdmit(userId, scope);
            if (admitted != null) {
                return admitted;
            }
            // 자리 없음(Lua FULL) → 아래 대기열 등록으로 이어짐
        }

        // Idempotency 체크 (중복 진입 방지)
        if (idempotencyKey != null) {
            String idemKey = IDEM_KEY_PREFIX + idempotencyKey;
            boolean isNew = redissonClient.getBucket(idemKey).setIfAbsent(userId.toString(), Duration.ofMinutes(5));
            if (!isNew) {
                // 이미 대기 중인 경우 현재 rank 반환
                return buildWaitingStatus(userId, scope);
            }
        }

        // Sorted Set에 추가 (score = 등록 epoch ms). Q6: 이미 대기 중이면 score를 갱신하지 않는다
        // (ZADD NX) — 새로고침 재진입 시 기존 순번이 맨 뒤로 밀리면 안 된다.
        double score = Instant.now().toEpochMilli();
        RScoredSortedSet<String> waitingSet = waitingSet(scope);
        waitingSet.addIfAbsent(score, userId.toString());

        // scope 목록에 추가 (스케줄러 순회용)
        redissonClient.getSet(SCOPES_KEY).add(scope);

        // scope 태그 없이 등록하면 모든 scope가 한 게이지로 합쳐져 최초 등록된 scope의 크기만 보인다.
        meterRegistry.gauge("xrail.queue.waiting.size", Tags.of("scope", scope), waitingSet, RScoredSortedSet::size);
        return buildWaitingStatus(userId, scope);
    }

    public QueueStatus getStatus(Long userId, String scope) {
        RBucket<String> bucket = redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE);
        String token = bucket.get();
        if (token != null) {
            long ttlMs = bucket.remainTimeToLive();
            long expiresAt = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : 0);
            return QueueStatus.active(token, expiresAt);
        }
        return buildWaitingStatus(userId, scope);
    }

    public void leave(Long userId, String scope) {
        waitingSet(scope).remove(userId.toString());
        redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE).delete();
        activeSet(scope).remove(userId.toString());
        redissonClient.getBucket(firstKeyName(scope, userId), StringCodec.INSTANCE).delete();
        meterRegistry.counter("xrail.queue.releases.total", "scope", scope, "reason", "left").increment();
    }

    /**
     * 스케줄러가 호출: capacity-aware 승급(§4.1). 빈 자리만큼만(min(batchSize, capacity)) 승급한다.
     * @return 승급된 userId 목록
     */
    public List<Long> promoteTopN(String scope, int batchSize) {
        long now = System.currentTimeMillis();
        long expiresAt = now + (long) activeTtlSeconds * 1000;

        List<String> promotedIds = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                promoteScript,
                RScript.ReturnType.MULTI,
                List.of(waitingKeyName(scope), activeSetKeyName(scope)),
                String.valueOf(maxActive), String.valueOf(batchSize), String.valueOf(now), String.valueOf(expiresAt)
        );

        List<Long> promoted = new ArrayList<>();
        if (promotedIds.isEmpty()) {
            if (getActiveCount(scope) >= maxActive) {
                meterRegistry.counter("xrail.queue.promote.skipped", "scope", scope).increment();
            }
            return promoted;
        }

        for (String userIdStr : promotedIds) {
            Long uid = Long.parseLong(userIdStr);
            // 토큰 issuedAt은 Lua에 넘긴 ARGV now를 그대로 쓴다(§4.1) — 새로 찍으면 토큰 exp가
            // active-set score(expiresAt)보다 늦게 끝나는 미세 창이 생긴다.
            String token = tokenService.generateToken(uid, scope, now);
            long remainMs = expiresAt - System.currentTimeMillis();
            redissonClient.getBucket(activeKeyName(scope, uid), StringCodec.INSTANCE)
                    .set(token, Duration.ofMillis(Math.max(remainMs, 1)));
            setFirstKey(scope, uid, now);
            promoted.add(uid);
        }

        meterRegistry.counter("xrail.queue.promotions.total").increment(promoted.size());
        log.info("Promoted {} users in scope={}", promoted.size(), scope);
        return promoted;
    }

    /**
     * §4.2 슬롯 반환: release.lua로 가드 판정 + ZREM/DEL을 원자 실행.
     * @return 실제로 반환됐으면 true (버킷 부재/ABA-skew로 skip이면 false)
     */
    public boolean releaseSlot(String scope, Long userId, long occurredAtMs) {
        Long released = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                releaseScript,
                RScript.ReturnType.INTEGER,
                List.of(activeKeyName(scope, userId), activeSetKeyName(scope), firstKeyName(scope, userId)),
                String.valueOf(userId), String.valueOf(occurredAtMs), String.valueOf(releaseSkewMarginMs)
        );
        boolean wasReleased = Long.valueOf(1).equals(released);
        if (wasReleased) {
            meterRegistry.counter("xrail.queue.releases.total", "scope", scope, "reason", "reserved").increment();
            recordRelease(scope);
        }
        return wasReleased;
    }

    public Set<String> getActiveScopes() {
        return redissonClient.<String>getSet(SCOPES_KEY).readAll();
    }

    public int getWaitingRank(Long userId, String scope) {
        Integer rank = waitingSet(scope).rank(userId.toString());
        return rank == null ? -1 : rank + 1; // 1-based
    }

    public int getWaitingSize(String scope) {
        return waitingSet(scope).size();
    }

    /**
     * 현재 동시 active(입장 허용) 인원. 만료된 항목은 조회 시 정리(score = 만료 epoch ms).
     */
    public int getActiveCount(String scope) {
        RScoredSortedSet<String> set = activeSet(scope);
        set.removeRangeByScore(0, true, System.currentTimeMillis(), true);
        return set.size();
    }

    /**
     * §5.2-상세: 최근 60초 반환 이동평균 기반 예상 대기시간. 표본이 부족하면(콜드스타트)
     * 기존 고정 처리량 가정 공식(ceil(rank/batchSize)*3)으로 폴백한다.
     */
    public int estimateWaitSeconds(String scope, int rank) {
        if (rank <= 0) return 0;

        long cutoff = System.currentTimeMillis() - WAIT_ESTIMATE_WINDOW_MS;
        Deque<Long> timestamps = recentReleases.get(scope);
        int recentCount = 0;
        if (timestamps != null) {
            for (Long ts : timestamps) {
                if (ts >= cutoff) recentCount++;
            }
        }

        if (recentCount < WAIT_ESTIMATE_MIN_SAMPLES) {
            return (int) Math.ceil((double) rank / batchSize) * 3;
        }

        double releaseRatePerSecond = recentCount / (WAIT_ESTIMATE_WINDOW_MS / 1000.0);
        return (int) Math.ceil(rank / releaseRatePerSecond);
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
    public Map<String, Object> modeSnapshot() {
        Set<String> scopes = new java.util.TreeSet<>(getActiveScopes());
        scopes.add("global");
        List<Map<String, Object>> scopeStats = new ArrayList<>();
        for (String scope : scopes) {
            scopeStats.add(Map.of(
                    "scope", scope,
                    "waiting", getWaitingSize(scope),
                    "active", getActiveCount(scope)
            ));
        }
        return Map.of(
                "mode", getMode(),
                "maxActive", maxActive,
                "scopes", scopeStats
        );
    }

    // ===== helpers =====

    private void validateScope(String scope) {
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new BusinessException(ErrorCode.INVALID_QUEUE_SCOPE);
        }
    }

    private void registerActiveGauge(String scope) {
        // Micrometer 게이지는 상태 객체를 약참조로 붙잡는다 — 요청에서 파싱된 scope 문자열을 그대로
        // 넘기면 GC 후 게이지가 영구 NaN이 된다. 맵에 강참조로 보관한 인스턴스로 1회만 등록한다.
        gaugedScopes.computeIfAbsent(scope, s -> {
            meterRegistry.gauge("xrail.queue.active.size", Tags.of("scope", s), s,
                    x -> (double) getActiveCount(x));
            return s;
        });
    }

    /** FORCE_OFF: 운영자 override로 용량 체크 없이 즉시 입장(표 5.9, INV-1 예외로 문서화된 리스크). */
    private QueueStatus admitBypassingCapacity(Long userId, String scope) {
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + (long) activeTtlSeconds * 1000;
        activeSet(scope).add(expiresAt, userId.toString());
        return finishAdmit(userId, scope, issuedAt, expiresAt);
    }

    /** AUTO: §4.4 원자적 슬롯 확보. 자리 없으면 null(호출측이 대기열 등록으로 폴백). */
    private QueueStatus tryAdmit(Long userId, String scope) {
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + (long) activeTtlSeconds * 1000;
        if (!tryAcquireSlot(scope, userId, issuedAt, expiresAt)) {
            return null;
        }
        return finishAdmit(userId, scope, issuedAt, expiresAt);
    }

    private boolean tryAcquireSlot(String scope, Long userId, long now, long expiresAt) {
        Long acquired = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ACQUIRE_SLOT_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(activeSetKeyName(scope)),
                String.valueOf(now), String.valueOf(maxActive), String.valueOf(expiresAt), String.valueOf(userId)
        );
        return Long.valueOf(1).equals(acquired);
    }

    /** 새 세션 확정: 토큰 발급 + 버킷 SET + waiting 정리 + first-키 SET(§4.6, 무조건 덮어쓰기). */
    private QueueStatus finishAdmit(Long userId, String scope, long issuedAt, long expiresAt) {
        String token = tokenService.generateToken(userId, scope, issuedAt);
        redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE)
                .set(token, Duration.ofSeconds(activeTtlSeconds));
        waitingSet(scope).remove(userId.toString());
        setFirstKey(scope, userId, issuedAt);

        meterRegistry.counter("xrail.queue.immediate.total").increment();
        return QueueStatus.active(token, expiresAt);
    }

    /**
     * §4.6 재발급: cap 이내면 TTL/score를 연장, cap 초과면 기존 토큰을 연장 없이 그대로 반환한다.
     * 재발급은 §4.4의 용량 체크를 타지 않는다(기존 점유자의 ZADD는 score 갱신일 뿐 카운트가 늘지 않음).
     */
    private QueueStatus reissue(Long userId, String scope) {
        long now = System.currentTimeMillis();
        Long firstIssuedAt = getFirstIssuedAt(scope, userId);

        if (firstIssuedAt != null && (now - firstIssuedAt) > (long) sessionCapSeconds * 1000) {
            // cap 초과: 연장 없이 기존 버킷 값(토큰) 그대로 반환 — 슬롯·버킷·토큰은 마지막 재발급
            // 시점 + activeTtlSeconds에 함께 만료(INV-2 유지).
            meterRegistry.counter("xrail.queue.reissue.capped.total", "scope", scope).increment();
            RBucket<String> bucket = redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE);
            String existingToken = bucket.get();
            long remainMs = bucket.remainTimeToLive();
            long expiresAt = now + Math.max(remainMs, 0);
            return QueueStatus.active(existingToken, expiresAt);
        }

        // cap 이내(또는 first-키 잔존 없음 → 비정상 상태를 새 세션으로 간주): 정상 재발급
        long issuedAt = now;
        long expiresAt = issuedAt + (long) activeTtlSeconds * 1000;
        String token = tokenService.generateToken(userId, scope, issuedAt);
        redissonClient.getBucket(activeKeyName(scope, userId), StringCodec.INSTANCE)
                .set(token, Duration.ofSeconds(activeTtlSeconds));
        activeSet(scope).add(expiresAt, userId.toString());

        if (firstIssuedAt == null) {
            // first-키가 없으면(TTL 만료/leave 후 잔존 없는 비정상 상태) 새 세션으로 간주해 SET.
            // firstIssuedAt이 있었다면(cap 이내 정상 재발급) first-키는 건드리지 않는다 — 갱신하면
            // cap이 무의미해진다.
            setFirstKey(scope, userId, issuedAt);
        }
        return QueueStatus.active(token, expiresAt);
    }

    private Long getFirstIssuedAt(String scope, Long userId) {
        String value = redissonClient.<String>getBucket(firstKeyName(scope, userId), StringCodec.INSTANCE).get();
        return value == null ? null : Long.valueOf(value);
    }

    private void setFirstKey(String scope, Long userId, long issuedAt) {
        long ttlSeconds = (long) sessionCapSeconds + activeTtlSeconds + 60;
        redissonClient.getBucket(firstKeyName(scope, userId), StringCodec.INSTANCE)
                .set(String.valueOf(issuedAt), Duration.ofSeconds(ttlSeconds));
    }

    private void recordRelease(String scope) {
        Deque<Long> timestamps = recentReleases.computeIfAbsent(scope, s -> new ConcurrentLinkedDeque<>());
        timestamps.addLast(System.currentTimeMillis());
        long cutoff = System.currentTimeMillis() - WAIT_ESTIMATE_WINDOW_MS;
        Long head;
        while ((head = timestamps.peekFirst()) != null && head < cutoff) {
            timestamps.pollFirst();
        }
    }

    private QueueStatus buildWaitingStatus(Long userId, String scope) {
        int rank = getWaitingRank(userId, scope);
        int total = getWaitingSize(scope);
        return QueueStatus.waiting(rank, total);
    }

    private RScoredSortedSet<String> waitingSet(String scope) {
        return redissonClient.getScoredSortedSet(waitingKeyName(scope), StringCodec.INSTANCE);
    }

    private RScoredSortedSet<String> activeSet(String scope) {
        return redissonClient.getScoredSortedSet(activeSetKeyName(scope), StringCodec.INSTANCE);
    }

    private String waitingKeyName(String scope) {
        return WAITING_KEY_PREFIX + scope;
    }

    private String activeSetKeyName(String scope) {
        return ACTIVE_SET_PREFIX + scope;
    }

    private String activeKeyName(String scope, Long userId) {
        return ACTIVE_KEY_PREFIX + scope + ":" + userId;
    }

    private String firstKeyName(String scope, Long userId) {
        return ACTIVE_FIRST_KEY_PREFIX + scope + ":" + userId;
    }

    private String loadResource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    // ===== inner result types =====

    public record QueueStatus(String status, int rank, int totalWaiting, String queueToken, long expiresAt) {
        static QueueStatus waiting(int rank, int total) {
            return new QueueStatus("WAITING", rank, total, null, 0);
        }
        static QueueStatus active(String token, long expiresAt) {
            return new QueueStatus("ACTIVE", 0, 0, token, expiresAt);
        }
    }
}
