package com.xrail.common.kafka.event;

/**
 * 환불 요청 이벤트. train-service가 PAID 예약 취소 시 발행, payment-service가 소비한다.
 * 금액은 payment-service가 자신의 결제 기록(amount - discount)으로 결정하므로 포함하지 않는다.
 */
public record PaymentRefundRequestedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long reservationId,
        Long userId,
        String reason  // USER_CANCEL | ADMIN_CANCEL
) {}
