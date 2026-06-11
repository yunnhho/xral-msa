package com.xrail.train.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.IntegerCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LuaScriptService {

    private final RedissonClient redissonClient;

    private String reserveScript;
    private String rollbackScript;
    private String checkFreeBatchScript;

    @PostConstruct
    public void loadScripts() throws IOException {
        reserveScript = loadResource("lua/reserve_seat.lua");
        rollbackScript = loadResource("lua/rollback_seat.lua");
        checkFreeBatchScript = loadResource("lua/check_free_batch.lua");
        log.info("Lua scripts loaded: reserve_seat.lua, rollback_seat.lua, check_free_batch.lua");
    }

    /**
     * Atomically reserves seat segment bits.
     * @return true if success, false if conflict
     */
    public boolean tryReserve(Long scheduleId, Long seatId, int startIdx, int endIdx) {
        String key = buildKey(scheduleId, seatId);
        Long result = redissonClient.getScript(IntegerCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                reserveScript,
                RScript.ReturnType.INTEGER,
                List.of(key),
                String.valueOf(startIdx), String.valueOf(endIdx)
        );
        return Long.valueOf(1).equals(result);
    }

    /**
     * Releases seat segment bits (compensation).
     */
    public void rollback(Long scheduleId, Long seatId, int startIdx, int endIdx) {
        String key = buildKey(scheduleId, seatId);
        redissonClient.getScript(IntegerCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                rollbackScript,
                RScript.ReturnType.INTEGER,
                List.of(key),
                String.valueOf(startIdx), String.valueOf(endIdx)
        );
    }

    /**
     * Checks if a bit range is free (read-only bitmap check).
     */
    public boolean isFree(Long scheduleId, Long seatId, int startIdx, int endIdx) {
        String key = buildKey(scheduleId, seatId);
        for (int i = startIdx; i < endIdx; i++) {
            if (redissonClient.getBitSet(key).get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks availability of multiple seats in a single Redis round trip.
     * 좌석별 GETBIT 왕복(N×segment)을 Lua 1회 호출로 배치 — 조회 경로 성능 핵심.
     * @return seatIds 순서대로 가용 여부
     */
    public List<Boolean> areFree(Long scheduleId, List<Long> seatIds, int startIdx, int endIdx) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        List<Object> keys = seatIds.stream()
                .map(seatId -> (Object) buildKey(scheduleId, seatId))
                .toList();
        List<Long> result = redissonClient.getScript(IntegerCodec.INSTANCE).eval(
                RScript.Mode.READ_ONLY,
                checkFreeBatchScript,
                RScript.ReturnType.MULTI,
                keys,
                String.valueOf(startIdx), String.valueOf(endIdx)
        );
        return result.stream().map(Long.valueOf(1L)::equals).toList();
    }

    private String buildKey(Long scheduleId, Long seatId) {
        return "sch:" + scheduleId + ":seat:" + seatId;
    }

    private String loadResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
