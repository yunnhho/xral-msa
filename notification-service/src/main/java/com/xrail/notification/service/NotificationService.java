package com.xrail.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.NotificationDispatchedEvent;
import com.xrail.notification.channel.NotificationChannel;
import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository logRepository;
    private final List<NotificationChannel> channels;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * N1: correlation_id UNIQUE 제약으로 멱등 처리.
     * 중복 INSERT 시 DataIntegrityViolationException → 정상 커밋 (이미 처리됨).
     */
    @Transactional
    public void dispatch(Long userId, String template, String correlationId, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize payload for correlationId={}", correlationId, e);
            return;
        }

        // 1차: INAPP 채널만 실발송
        for (NotificationChannel channel : channels) {
            try {
                NotificationLog notifLog = logRepository.save(NotificationLog.builder()
                        .userId(userId)
                        .channel(channel.getChannelName())
                        .template(template)
                        .correlationId(correlationId)
                        .payloadJson(payloadJson)
                        .build());

                sendAsync(notifLog, channel, userId, template, payloadJson);

            } catch (DataIntegrityViolationException e) {
                // N1: correlation_id + channel 중복 → 이미 처리됨. 정상 skip.
                log.debug("Already processed correlationId={} channel={}", correlationId, channel.getChannelName());
            }
        }
    }

    // N5: 채널 전송은 @Async — 전송 실패가 Kafka 커밋을 막지 않도록
    @Async
    public void sendAsync(NotificationLog notifLog, NotificationChannel channel,
                           Long userId, String template, String payloadJson) {
        try {
            channel.send(userId, template, payloadJson);
            notifLog.markSent();
            logRepository.save(notifLog);
            publishDispatched(notifLog);
        } catch (Exception e) {
            log.error("Channel send failed notifId={} channel={}", notifLog.getId(), channel.getChannelName(), e);
            notifLog.markFailed(e.getMessage());
            logRepository.save(notifLog);
        }
    }

    private void publishDispatched(NotificationLog notifLog) {
        NotificationDispatchedEvent event = new NotificationDispatchedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                notifLog.getId(),
                notifLog.getUserId(),
                notifLog.getChannel(),
                notifLog.getTemplate(),
                notifLog.getCorrelationId()
        );
        kafkaTemplate.send(Topics.NOTIFICATION_DISPATCHED, String.valueOf(notifLog.getUserId()), event);
    }
}
