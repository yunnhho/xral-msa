package com.xrail.train.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.train.entity.OutboxEvent;
import com.xrail.train.entity.enums.OutboxStatus;
import com.xrail.train.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * P4: outbox 테이블의 PENDING 이벤트를 폴링하여 Kafka로 발행한다 (at-least-once).
 * 컨슈머가 eventId로 멱등 처리하므로 중복 발행은 안전하다.
 * 저장된 payload를 원래 이벤트 타입으로 역직렬화해 보내므로 JsonSerializer의 __TypeId__
 * 헤더가 직접 발행할 때와 동일하게 부여된다 (컨슈머 호환).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${train.outbox.relay-interval-ms:1000}")
    public void relay() {
        List<OutboxEvent> batch = outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        if (batch.isEmpty()) return;

        for (OutboxEvent e : batch) {
            try {
                Object event = objectMapper.readValue(e.getPayload(), Class.forName(e.getEventType()));
                kafkaTemplate.send(e.getTopic(), e.getEventKey(), event).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                e.markSent();
                outboxRepository.save(e);
                meterRegistry.counter("xrail.train.outbox.published.total").increment();
            } catch (Exception ex) {
                // 발행 실패 → PENDING 유지, 다음 주기에 재시도. 한 건 실패가 배치를 막지 않는다.
                log.warn("Outbox relay 실패 id={} topic={} reason={}", e.getId(), e.getTopic(), ex.getMessage());
            }
        }
    }
}
