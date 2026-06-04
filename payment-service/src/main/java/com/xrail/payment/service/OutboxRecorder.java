package com.xrail.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.payment.entity.OutboxEvent;
import com.xrail.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P4: 이벤트를 직접 Kafka로 보내지 않고 비즈니스 트랜잭션과 같은 트랜잭션에서 outbox 테이블에 기록한다.
 * 실제 발행은 {@link com.xrail.payment.scheduler.OutboxRelayScheduler}가 커밋 후 수행한다.
 * 호출자의 @Transactional 범위 안에서 호출되어야 원자성이 보장된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void record(String aggregateType, String aggregateId, String topic, String key, Object event) {
        String payload;
        String eventId;
        try {
            payload = objectMapper.writeValueAsString(event);
            JsonNode node = objectMapper.readTree(payload);
            eventId = node.hasNonNull("eventId") ? node.get("eventId").asText() : null;
        } catch (Exception e) {
            // 직렬화 실패 = 트랜잭션을 깨야 한다 (이벤트 유실 방지).
            log.error("Outbox 직렬화 실패 topic={} type={}", topic, event.getClass().getName(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        outboxRepository.save(OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .topic(topic)
                .eventKey(key)
                .eventType(event.getClass().getName())
                .eventId(eventId)
                .payload(payload)
                .build());
    }
}
