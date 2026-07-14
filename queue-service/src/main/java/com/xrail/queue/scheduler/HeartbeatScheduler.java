package com.xrail.queue.scheduler;

import com.xrail.queue.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatScheduler {

    private final SseEmitterRegistry emitterRegistry;
    private final com.xrail.queue.service.QueueService queueService;

    @Scheduled(fixedDelayString = "${queue.sse.heartbeat-ms:25000}")
    public void sendHeartbeats() {
        Set<String> scopes = queueService.getActiveScopes();
        for (String scope : scopes) {
            Map<Long, SseEmitter> emitters = emitterRegistry.getAll(scope);
            for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
                try {
                    entry.getValue().send(SseEmitter.event().name("heartbeat").data("{}"));
                } catch (IOException e) {
                    emitterRegistry.remove(scope, entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
