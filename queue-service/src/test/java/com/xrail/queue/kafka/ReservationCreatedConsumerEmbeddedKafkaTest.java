package com.xrail.queue.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.ReservationCreatedEvent;
import com.xrail.queue.service.QueueService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * P8: Kafka 통합 테스트는 @EmbeddedKafka 사용. 전체 앱 컨텍스트(Eureka/Redis 등)는 이 배선 검증과
 * 무관하므로 컨슈머 + 최소 Kafka 리스너 인프라만 구성한다(P8: @SpringBootTest 남용 금지).
 * 목적: @KafkaListener(topics=RESERVATION_CREATED, groupId="queue-service")가 실제 브로커에서
 * 메시지를 수신·역직렬화해 QueueService.releaseSlot을 호출하는 end-to-end 배선을 검증한다.
 * releaseSlot 자체의 인자 로직은 ReservationCreatedConsumerTest(순수 Mockito)에서 검증한다.
 */
@SpringJUnitConfig(ReservationCreatedConsumerEmbeddedKafkaTest.TestConfig.class)
@EmbeddedKafka(partitions = 1, topics = {Topics.RESERVATION_CREATED})
class ReservationCreatedConsumerEmbeddedKafkaTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private QueueService queueService;

    @Test
    void onReservationCreated_consumesFromRealBroker_andReleasesSlots() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        ProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, Object> producer = new KafkaTemplate<>(producerFactory);

        ReservationCreatedEvent event = new ReservationCreatedEvent(
                "evt-1",
                Instant.ofEpochMilli(1_700_000_000_000L).toString(),
                "trace-1",
                100L, 42L, "tester",
                7L, List.of(1L, 2L), 0, 3, 10000L,
                Instant.now().toString()
        );

        producer.send(Topics.RESERVATION_CREATED, String.valueOf(event.reservationId()), event);

        verify(queueService, timeout(10_000)).releaseSlot(eq("global"), eq(42L), eq(1_700_000_000_000L));
        verify(queueService, timeout(10_000)).releaseSlot(eq("schedule:7"), eq(42L), eq(1_700_000_000_000L));
    }

    @Configuration
    @EnableKafka
    static class TestConfig {

        @Bean
        public QueueService queueService() {
            return mock(QueueService.class);
        }

        @Bean
        public ReservationCreatedConsumer reservationCreatedConsumer(QueueService queueService) {
            return new ReservationCreatedConsumer(queueService);
        }

        @Bean
        public ConsumerFactory<String, Object> consumerFactory(EmbeddedKafkaBroker broker) {
            Map<String, Object> props = KafkaTestUtils.consumerProps("queue-service", "true", broker);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.xrail.common.kafka.event");
            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
                ConsumerFactory<String, Object> consumerFactory) {
            ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumerFactory);
            return factory;
        }
    }
}
