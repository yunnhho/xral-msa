package com.xrail.notification.channel;

public interface NotificationChannel {
    String getChannelName();
    void send(Long userId, String template, String payloadJson);
}
