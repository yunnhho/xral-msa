package com.xrail.queue.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.ReservationCreatedEvent;
import com.xrail.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * §4.2: 예약 생성(reservation.created) = 사용자가 보호 구역을 이탈한 시점 → 대기열 슬롯 반환.
 * scope 이중 반환(global + schedule:{id})은 표 5.7 방어책 — 반환은 멱등(ZREM/DEL no-op)이라
 * 두 후보 scope 모두 시도해도 안전하다. scheduleId가 없으면 schedule scope 반환은 생략한다.
 *
 * P4 명시적 예외(§4.2): releaseSlot 자체가 멱등 + ABA 가드로 순서 안전까지 겸해 별도 eventId
 * dedup 셋을 두지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationCreatedConsumer {

    private final QueueService queueService;

    @KafkaListener(topics = Topics.RESERVATION_CREATED, groupId = "queue-service")
    public void onReservationCreated(ReservationCreatedEvent event) {
        MDC.put("traceId", event.traceId());
        try {
            long occurredAtMs = Instant.parse(event.occurredAt()).toEpochMilli();
            log.info("Received reservation.created reservationId={} userId={} scheduleId={} eventId={}",
                    event.reservationId(), event.userId(), event.scheduleId(), event.eventId());

            queueService.releaseSlot("global", event.userId(), occurredAtMs);
            if (event.scheduleId() != null) {
                queueService.releaseSlot("schedule:" + event.scheduleId(), event.userId(), occurredAtMs);
            }
        } finally {
            MDC.remove("traceId");
        }
    }
}
