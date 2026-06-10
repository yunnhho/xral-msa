package com.xrail.train.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.SeatLockedEvent;
import com.xrail.train.entity.OutboxEvent;
import com.xrail.train.entity.enums.OutboxStatus;
import com.xrail.train.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock private OutboxEventRepository outboxRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private MeterRegistry meterRegistry;

    private OutboxRelayScheduler scheduler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Counter counter = mock(Counter.class);
        lenient().when(meterRegistry.counter(anyString())).thenReturn(counter);
        scheduler = new OutboxRelayScheduler(outboxRepository, kafkaTemplate, objectMapper, meterRegistry);
    }

    private OutboxEvent pendingEvent() throws Exception {
        SeatLockedEvent event = new SeatLockedEvent("evt-1", "2026-06-10T00:00:00Z", "trace-1",
                1L, 2L, List.of(3L));
        return OutboxEvent.builder()
                .aggregateType("Reservation")
                .aggregateId("1")
                .topic(Topics.SEAT_LOCKED)
                .eventKey("1")
                .eventType(SeatLockedEvent.class.getName())
                .eventId("evt-1")
                .payload(objectMapper.writeValueAsString(event))
                .build();
    }

    @Test
    void relay_pendingEvent_publishesAndMarksSent() throws Exception {
        OutboxEvent outbox = pendingEvent();
        when(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(outbox));
        when(kafkaTemplate.send(eq(Topics.SEAT_LOCKED), eq("1"), any(SeatLockedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        scheduler.relay();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(outbox.getSentAt()).isNotNull();
        verify(outboxRepository).save(outbox);
    }

    @Test
    void relay_sendFailure_keepsPending() throws Exception {
        OutboxEvent outbox = pendingEvent();
        when(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(outbox));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        scheduler.relay();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void relay_oneFailure_doesNotBlockOthers() throws Exception {
        OutboxEvent broken = pendingEvent();
        OutboxEvent ok = pendingEvent();
        when(outboxRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(broken, ok));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        scheduler.relay();

        assertThat(broken.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(ok.getStatus()).isEqualTo(OutboxStatus.SENT);
    }
}
