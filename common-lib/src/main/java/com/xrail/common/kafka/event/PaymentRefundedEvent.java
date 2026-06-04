package com.xrail.common.kafka.event;

/**
 * 환불 완료 이벤트. payment-service가 PG 환불 성공 후 발행한다.
 * train-service(사가 로그)와 notification-service(환불 완료 알림)가 소비한다.
 */
public record PaymentRefundedEvent(
        String eventId,
        String occurredAt,
        String traceId,
        Long paymentId,
        Long reservationId,
        Long userId,
        Long amount  // 실제 환불된 금액 (할인 적용 후 청구액)
) {}
