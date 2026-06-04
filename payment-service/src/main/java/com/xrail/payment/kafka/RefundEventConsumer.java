package com.xrail.payment.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.PaymentRefundRequestedEvent;
import com.xrail.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 환불 사가: train-service가 PAID 예약 취소 시 발행하는 payment.refund-requested를 소비한다.
 * 처리 실패 시 예외를 던져 KafkaConfig의 DefaultErrorHandler → DLT로 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundEventConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics = Topics.PAYMENT_REFUND_REQUESTED, groupId = "payment-service")
    public void onRefundRequested(PaymentRefundRequestedEvent event) {
        MDC.put("traceId", event.traceId() != null ? event.traceId() : "");
        try {
            log.info("Received payment.refund-requested reservationId={} reason={} eventId={}",
                    event.reservationId(), event.reason(), event.eventId());
            paymentService.refund(event.reservationId(), event.reason());
        } finally {
            MDC.remove("traceId");
        }
    }
}
