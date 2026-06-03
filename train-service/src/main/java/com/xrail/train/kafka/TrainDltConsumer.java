package com.xrail.train.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.train.entity.DltAlertLog;
import com.xrail.train.repository.DltAlertLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrainDltConsumer {

    private final DltAlertLogRepository dltAlertLogRepository;

    // T3: 결제 완료 컨슈머 실패 → DLT 격리. 예외 throw 금지 (무한 루프 방지).
    @KafkaListener(topics = Topics.PAYMENT_COMPLETED_DLT, groupId = "train-dlt-service")
    public void handlePaymentCompletedDlt(ConsumerRecord<String, Object> record) {
        saveDltAlert(record);
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED_DLT, groupId = "train-dlt-service")
    public void handlePaymentFailedDlt(ConsumerRecord<String, Object> record) {
        saveDltAlert(record);
    }

    private void saveDltAlert(ConsumerRecord<String, Object> record) {
        String traceId = extractHeader(record, "traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            String errorMessage = extractDltErrorHeader(record);
            log.error("[DLT][train-service] 처리 실패 메시지 격리. topic={} partition={} offset={} key={} error={}",
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
                // DB 저장 실패해도 DLT 처리는 정상 커밋 (이미 로그로 기록됨)
                log.error("[DLT][train-service] DltAlertLog 저장 실패. topic={}", record.topic(), e);
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
