package com.xrail.payment.kafka;

import com.xrail.common.kafka.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * P5: DLT 메시지 격리 핸들러.
 * payment.completed.DLT에 도착한 메시지 → 운영 로그 + 향후 알람 연동.
 * 예외를 throw하지 않는다 (P5 규칙).
 */
@Slf4j
@Component
public class PaymentDltConsumer {

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED_DLT, groupId = "payment-dlt-service")
    public void handleDlt(ConsumerRecord<String, Object> record) {
        log.error("[DLT] payment.completed message failed after retries. " +
                        "topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        // TODO: Slack/알람 연동 시 여기에 추가
    }
}
