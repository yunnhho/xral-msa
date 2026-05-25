package com.xrail.notification.service;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.NotificationDispatchedEvent;
import com.xrail.notification.channel.NotificationChannel;
import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * N5: @TransactionalEventListener(AFTER_COMMIT) + @Async
 * dispatch() 트랜잭션이 커밋된 후 채널 전송을 별도 스레드에서 처리.
 * DB INSERT가 완료된 후에 채널 전송하므로 엔티티 조회가 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationChannelSender {

    private final NotificationLogRepository logRepository;
    private final List<NotificationChannel> channels;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleSend(NotificationChannelSendEvent event) {
        NotificationLog notifLog = logRepository.findById(event.notifLogId()).orElse(null);
        if (notifLog == null) {
            log.error("NotificationLog not found id={}", event.notifLogId());
            return;
        }

        NotificationChannel channel = channels.stream()
                .filter(c -> c.getChannelName().equals(event.channelName()))
                .findFirst()
                .orElse(null);
        if (channel == null) {
            log.error("Channel not found name={}", event.channelName());
            return;
        }

        try {
            channel.send(event.userId(), event.template(), event.payloadJson());
            notifLog.markSent();
            logRepository.save(notifLog);
            publishDispatched(notifLog);
        } catch (Exception e) {
            log.error("Channel send failed notifId={} channel={}", notifLog.getId(), event.channelName(), e);
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
