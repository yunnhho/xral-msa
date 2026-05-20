package com.xrail.queue.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 SSE emitter 저장소.
 * 1차: 단일 인스턴스 가정. 수평 확장 시 Redis pub/sub으로 교체 필요.
 */
@Component
public class SseEmitterRegistry {

    // scope → (userId → emitter)
    private final Map<String, Map<Long, SseEmitter>> registry = new ConcurrentHashMap<>();

    public void register(String scope, Long userId, SseEmitter emitter) {
        registry.computeIfAbsent(scope, k -> new ConcurrentHashMap<>()).put(userId, emitter);
    }

    public SseEmitter get(String scope, Long userId) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        return scopeMap == null ? null : scopeMap.get(userId);
    }

    public void remove(String scope, Long userId) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        if (scopeMap != null) scopeMap.remove(userId);
    }

    public Map<Long, SseEmitter> getAll(String scope) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        return scopeMap == null ? Collections.emptyMap() : Collections.unmodifiableMap(scopeMap);
    }
}
