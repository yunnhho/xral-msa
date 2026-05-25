package com.xrail.notification.service;

public record NotificationChannelSendEvent(
        Long notifLogId,
        String channelName,
        Long userId,
        String template,
        String payloadJson
) {}
