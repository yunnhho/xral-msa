package com.xrail.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.notification.channel.NotificationChannel;
import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository logRepository;
    private final List<NotificationChannel> channels;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

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

        // N1: 채널별 INSERT 시도. UNIQUE 위반 시 이미 처리됨으로 skip.
        // N5: 채널 전송은 트랜잭션 커밋 후 @Async로 처리 (NotificationChannelSender).
        for (NotificationChannel channel : channels) {
            try {
                NotificationLog notifLog = logRepository.save(NotificationLog.builder()
                        .userId(userId)
                        .channel(channel.getChannelName())
                        .template(template)
                        .correlationId(correlationId)
                        .payloadJson(payloadJson)
                        .build());

                // AFTER_COMMIT 이벤트 — 트랜잭션 커밋 후에만 전송
                eventPublisher.publishEvent(new NotificationChannelSendEvent(
                        notifLog.getId(), channel.getChannelName(), userId, template, payloadJson));

            } catch (DataIntegrityViolationException e) {
                log.debug("Already processed correlationId={} channel={}", correlationId, channel.getChannelName());
            }
        }
    }
}
