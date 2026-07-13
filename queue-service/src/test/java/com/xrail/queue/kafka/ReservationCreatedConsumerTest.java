package com.xrail.queue.kafka;

import com.xrail.common.kafka.event.ReservationCreatedEvent;
import com.xrail.queue.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * §4.2: reservation.created 수신 시 releaseSlot 호출 로직(occurredAt 변환, scope 이중 반환,
 * scheduleId null 가드) 검증. Kafka 리스너 배선 자체(토픽/groupId/역직렬화)는
 * ReservationCreatedConsumerEmbeddedKafkaTest에서 별도 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationCreatedConsumerTest {

    @Mock
    private QueueService queueService;

    private ReservationCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReservationCreatedConsumer(queueService);
    }

    @Test
    void withScheduleId_releasesBothGlobalAndScheduleScope() {
        ReservationCreatedEvent event = event(7L, Instant.ofEpochMilli(1_700_000_000_000L).toString());

        consumer.onReservationCreated(event);

        verify(queueService).releaseSlot(eq("global"), eq(42L), eq(1_700_000_000_000L));
        verify(queueService).releaseSlot(eq("schedule:7"), eq(42L), eq(1_700_000_000_000L));
    }

    @Test
    void nullScheduleId_releasesOnlyGlobalScope() {
        ReservationCreatedEvent event = event(null, Instant.ofEpochMilli(1_700_000_000_000L).toString());

        consumer.onReservationCreated(event);

        verify(queueService).releaseSlot(eq("global"), eq(42L), eq(1_700_000_000_000L));
        verify(queueService, times(1)).releaseSlot(anyString(), anyLong(), anyLong());
        verify(queueService, never()).releaseSlot(eq("schedule:null"), anyLong(), anyLong());
    }

    @Test
    void occurredAt_isoStringParsedToEpochMillis() {
        String iso = "2026-07-13T10:00:00.500Z";
        ReservationCreatedEvent event = event(null, iso);

        consumer.onReservationCreated(event);

        verify(queueService).releaseSlot(eq("global"), eq(42L), eq(Instant.parse(iso).toEpochMilli()));
    }

    private ReservationCreatedEvent event(Long scheduleId, String occurredAt) {
        return new ReservationCreatedEvent(
                "evt-1", occurredAt, "trace-1", 100L, 42L, "tester",
                scheduleId, List.of(1L, 2L), 0, 3, 10000L, Instant.now().toString()
        );
    }
}
