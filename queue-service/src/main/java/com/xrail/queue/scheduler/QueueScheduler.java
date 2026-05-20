package com.xrail.queue.scheduler;

import com.xrail.queue.service.QueueService;
import com.xrail.queue.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    @Value("${queue.scheduler.batch-size:100}")
    private int batchSize;

    private final QueueService queueService;
    private final SseEmitterRegistry emitterRegistry;

    @Scheduled(fixedDelayString = "${queue.scheduler.delay-ms:3000}")
    public void tick() {
        Set<String> scopes = queueService.getActiveScopes();
        for (String scope : scopes) {
            try {
                processScopePromotion(scope);
                pushRankUpdates(scope);
            } catch (Exception e) {
                log.error("Scheduler error for scope={}", scope, e);
            }
        }
    }

    private void processScopePromotion(String scope) {
        List<Long> promoted = queueService.promoteTopN(scope, batchSize);
        for (Long userId : promoted) {
            QueueService.QueueStatus status = queueService.getStatus(userId, scope);
            // SSE: active 이벤트 전송
            SseEmitter emitter = emitterRegistry.get(scope, userId);
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("active")
                            .data(Map.of(
                                    "queueToken", status.queueToken(),
                                    "expiresAt", Instant.ofEpochMilli(status.expiresAt()).toString()
                            )));
                    emitter.complete();
                } catch (IOException e) {
                    emitterRegistry.remove(scope, userId);
                }
            }
        }
    }

    private void pushRankUpdates(String scope) {
        // 대기 중인 모든 SSE emitter에 rank 업데이트 전송
        Map<Long, SseEmitter> emitters = emitterRegistry.getAll(scope);
        for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
            Long userId = entry.getKey();
            SseEmitter emitter = entry.getValue();
            int rank = queueService.getWaitingRank(userId, scope);
            int total = queueService.getWaitingSize(scope);
            if (rank <= 0) continue; // 이미 active이거나 대기열에서 빠짐
            try {
                emitter.send(SseEmitter.event()
                        .name("rank")
                        .data(Map.of(
                                "rank", rank,
                                "totalWaiting", total,
                                "expectedWaitSeconds", estimateWaitSeconds(rank)
                        )));
            } catch (IOException e) {
                emitterRegistry.remove(scope, userId);
            }
        }
    }

    private int estimateWaitSeconds(int rank) {
        // 3초마다 100명 승급 → 1명당 0.03초, rank명 대기 → ceil(rank/100) * 3초
        return (int) Math.ceil((double) rank / batchSize) * 3;
    }
}
