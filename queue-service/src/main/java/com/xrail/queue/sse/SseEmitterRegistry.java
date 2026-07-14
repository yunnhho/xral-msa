package com.xrail.queue.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 SSE emitter 저장소.
 * 인스턴스 간 이벤트 전파는 RedisQueueEventListener(RTopic pub/sub)가 담당한다.
 */
@Component
public class SseEmitterRegistry {

    // scope → (userId → emitter)
    private final Map<String, Map<Long, SseEmitter>> registry = new ConcurrentHashMap<>();

    /**
     * Q3: 동일 userId가 새 연결을 열면 기존 emitter를 complete() 처리 후 교체한다.
     * 기존 emitter의 onCompletion 콜백은 value-조건부 remove를 사용하므로
     * 방금 등록한 새 emitter를 지우지 않는다.
     */
    public void register(String scope, Long userId, SseEmitter emitter) {
        Map<Long, SseEmitter> scopeMap = registry.computeIfAbsent(scope, k -> new ConcurrentHashMap<>());
        SseEmitter previous = scopeMap.put(userId, emitter);
        if (previous != null && previous != emitter) {
            try {
                previous.complete();
            } catch (Exception ignored) {
                // 이미 닫힌 emitter일 수 있음 — 무시
            }
        }
    }

    public SseEmitter get(String scope, Long userId) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        return scopeMap == null ? null : scopeMap.get(userId);
    }

    /**
     * 현재 매핑이 정확히 이 emitter일 때만 제거한다.
     * stale 콜백(이미 교체된 옛 emitter의 onCompletion)이 새 emitter를 지우는 것을 방지.
     */
    public void remove(String scope, Long userId, SseEmitter emitter) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        if (scopeMap != null) scopeMap.remove(userId, emitter);
    }

    public Map<Long, SseEmitter> getAll(String scope) {
        Map<Long, SseEmitter> scopeMap = registry.get(scope);
        return scopeMap == null ? Collections.emptyMap() : Collections.unmodifiableMap(scopeMap);
    }
}
