package com.xrail.notification.service;

import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * N1: 채널별 멱등 INSERT를 독립 트랜잭션(REQUIRES_NEW)으로 격리한다.
 * UNIQUE 위반 시 해당 채널 트랜잭션만 롤백되고 호출측(dispatch)은 영향받지 않는다.
 * 단일 트랜잭션 안에서 DataIntegrityViolationException을 catch하면 트랜잭션이
 * rollback-only로 마킹되어 커밋 시점에 UnexpectedRollbackException이 발생한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationLogWriter {

    private final NotificationLogRepository logRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndPublish(Long userId, String channelName, String template,
                               String correlationId, String payloadJson) {
        NotificationLog notifLog = logRepository.save(NotificationLog.builder()
                .userId(userId)
                .channel(channelName)
                .template(template)
                .correlationId(correlationId)
                .payloadJson(payloadJson)
                .build());

        // AFTER_COMMIT 이벤트 — 이 REQUIRES_NEW 트랜잭션 커밋 후에만 채널 전송
        eventPublisher.publishEvent(new NotificationChannelSendEvent(
                notifLog.getId(), channelName, userId, template, payloadJson));
    }
}
