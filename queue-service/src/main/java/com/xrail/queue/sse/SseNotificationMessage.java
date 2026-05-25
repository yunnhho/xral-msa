package com.xrail.queue.sse;

import java.io.Serializable;
import java.util.Map;

/**
 * Redis RTopic을 통해 인스턴스 간 SSE 이벤트를 전파하는 메시지.
 * Q3: 수평 확장 시 각 인스턴스가 구독하고 로컬 emitter로 전달한다.
 */
public record SseNotificationMessage(
        String scope,
        Long userId,
        String eventName,
        Map<String, Object> data
) implements Serializable {}
