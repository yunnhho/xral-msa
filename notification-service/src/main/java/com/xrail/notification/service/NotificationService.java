package com.xrail.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.notification.channel.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationChannel> channels;
    private final ObjectMapper objectMapper;
    private final NotificationLogWriter logWriter;

    /**
     * N1: correlation_id UNIQUE 제약으로 멱등 처리.
     * 채널별 INSERT는 NotificationLogWriter의 독립 트랜잭션에서 수행되며,
     * 중복(UNIQUE 위반) 시 DataIntegrityViolationException → 해당 채널만 skip (정상 처리).
     */
    public void dispatch(Long userId, String template, String correlationId, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for correlationId={}", correlationId, e);
            return;
        }

        for (NotificationChannel channel : channels) {
            try {
                logWriter.saveAndPublish(userId, channel.getChannelName(), template, correlationId, payloadJson);
            } catch (DataIntegrityViolationException e) {
                log.debug("Already processed correlationId={} channel={}", correlationId, channel.getChannelName());
            }
        }
    }
}
