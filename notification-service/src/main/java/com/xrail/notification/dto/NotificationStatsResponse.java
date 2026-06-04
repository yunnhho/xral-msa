package com.xrail.notification.dto;

/**
 * 운영자 알림 발송 모니터링 집계. failed가 쌓이면 채널 전송 장애 신호.
 */
public record NotificationStatsResponse(
        long total,
        long sent,
        long pending,
        long failed
) {}
