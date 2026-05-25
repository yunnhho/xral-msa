package com.xrail.queue.sse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Q3: Redis RTopic 구독 → 로컬 SseEmitterRegistry에 이벤트 전달.
 * 각 인스턴스가 독립적으로 구독하므로 수평 확장 시에도 정상 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisQueueEventListener {

    public static final String TOPIC_NAME = "queue:sse:notify";

    private final RedissonClient redissonClient;
    private final SseEmitterRegistry emitterRegistry;

    private int listenerId;

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(TOPIC_NAME, new SerializationCodec());
        listenerId = topic.addListener(SseNotificationMessage.class, (channel, msg) -> {
            SseEmitter emitter = emitterRegistry.get(msg.scope(), msg.userId());
            if (emitter == null) return; // 이 인스턴스에 연결된 emitter 없음 — 다른 인스턴스가 처리
            try {
                emitter.send(SseEmitter.event().name(msg.eventName()).data(msg.data()));
                if ("active".equals(msg.eventName())) {
                    emitter.complete();
                }
            } catch (IOException e) {
                emitterRegistry.remove(msg.scope(), msg.userId());
            } catch (Exception e) {
                log.warn("SSE send error userId={} event={}", msg.userId(), msg.eventName(), e);
                emitterRegistry.remove(msg.scope(), msg.userId());
            }
        });
        log.info("Subscribed to Redis topic={} listenerId={}", TOPIC_NAME, listenerId);
    }

    @PreDestroy
    public void unsubscribe() {
        RTopic topic = redissonClient.getTopic(TOPIC_NAME, new SerializationCodec());
        topic.removeListener(listenerId);
    }
}
