package com.xrail.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InAppChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "INAPP";
    }

    @Override
    public void send(Long userId, String template, String payloadJson) {
        // 1차: INAPP 채널 stub — 로그만 출력 (향후 웹소켓/SSE 연동)
        log.info("[INAPP] userId={} template={} payload={}", userId, template, payloadJson);
    }
}
