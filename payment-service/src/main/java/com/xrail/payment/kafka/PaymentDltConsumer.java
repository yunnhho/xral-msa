package com.xrail.payment.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.payment.entity.DltAlertLog;
import com.xrail.payment.repository.DltAlertLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * P5: payment.completed.DLT 격리 핸들러.
 * train-service 컨슈머가 처리 실패하면 이 토픽으로 격리.
 * 예외를 throw하지 않는다 — DLT에서 또 실패하면 무한 루프.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentDltConsumer {

    private final DltAlertLogRepository dltAlertLogRepository;

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED_DLT, groupId = "payment-dlt-service")
    public void handleDlt(ConsumerRecord<String, Object> record) {
        String traceId = extractHeader(record, "traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            String errorMessage = extractDltErrorHeader(record);
            log.error("[DLT][payment-service] payment.completed 처리 실패. topic={} partition={} offset={} key={} error={}",
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
                log.error("[DLT][payment-service] DltAlertLog 저장 실패. topic={}", record.topic(), e);
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    private String extractHeader(ConsumerRecord<String, Object> record, String headerName) {
        var header = record.headers().lastHeader(headerName);
        if (header == null) return null;
        return new String(header.value());
    }

    private String extractDltErrorHeader(ConsumerRecord<String, Object> record) {
        return extractHeader(record, "kafka_dlt-exception-message");
    }
}
