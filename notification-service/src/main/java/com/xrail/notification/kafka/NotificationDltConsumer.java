package com.xrail.notification.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.notification.entity.DltAlertLog;
import com.xrail.notification.repository.DltAlertLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * N1: DLT 격리 핸들러. 6개 구독 토픽의 DLT 메시지를 처리.
 * 예외를 throw하지 않는다 — DLT에서 또 실패하면 무한 루프.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDltConsumer {

    private final DltAlertLogRepository dltAlertLogRepository;

    @KafkaListener(
            topics = {
                Topics.USER_SIGNED_UP_DLT,
                Topics.RESERVATION_CREATED_DLT,
                Topics.PAYMENT_COMPLETED_DLT,
                Topics.PAYMENT_FAILED_DLT,
                Topics.SEAT_CONFIRMED_DLT,
                Topics.SEAT_RELEASED_DLT
            },
            groupId = "notification-dlt-service"
    )
    public void handleDlt(ConsumerRecord<String, Object> record) {
        String errorMessage = extractDltErrorHeader(record);
        log.error("[DLT][notification-service] 알림 처리 실패. topic={} partition={} offset={} key={} error={}",
                record.topic(), record.partition(), record.offset(), record.key(), errorMessage);

        try {
            dltAlertLogRepository.save(DltAlertLog.builder()
                    .topic(record.topic())
                    .partition(record.partition())
                    .offset(record.offset())
                    .recordKey(record.key() != null ? record.key().toString() : null)
                    .recordValue(record.value() != null ? record.value().toString() : null)
                    .errorMessage(errorMessage)
                    .build());
        } catch (Exception e) {
            log.error("[DLT][notification-service] DltAlertLog 저장 실패. topic={}", record.topic(), e);
        }
    }

    private String extractDltErrorHeader(ConsumerRecord<String, Object> record) {
        var header = record.headers().lastHeader("kafka_dlt-exception-message");
        if (header == null) return null;
        return new String(header.value());
    }
}
