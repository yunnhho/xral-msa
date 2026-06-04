package com.xrail.queue.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    void register_replacingExistingUser_completesPreviousEmitter() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        registry.register("global", 1L, first);
        registry.register("global", 1L, second); // Q3: 기존 연결 정리 후 교체

        verify(first).complete();
        assertThat(registry.get("global", 1L)).isSameAs(second);
    }

    @Test
    void remove_staleEmitter_doesNotEvictNewerEmitter() {
        SseEmitter stale = mock(SseEmitter.class);
        SseEmitter current = mock(SseEmitter.class);

        registry.register("global", 1L, stale);
        registry.register("global", 1L, current);

        // 옛 emitter의 onCompletion 콜백이 뒤늦게 도착한 상황
        registry.remove("global", 1L, stale);

        assertThat(registry.get("global", 1L)).isSameAs(current);
    }

    @Test
    void remove_matchingEmitter_evicts() {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register("global", 1L, emitter);

        registry.remove("global", 1L, emitter);

        assertThat(registry.get("global", 1L)).isNull();
    }
}
