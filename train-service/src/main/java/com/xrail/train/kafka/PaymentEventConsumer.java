package com.xrail.train.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.PaymentCompletedEvent;
import com.xrail.common.kafka.event.PaymentFailedEvent;
import com.xrail.train.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final ReservationService reservationService;

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED, groupId = "train-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received payment.completed reservationId={} eventId={}", event.reservationId(), event.eventId());
        reservationService.handlePaymentCompleted(event.reservationId());
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = "train-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received payment.failed reservationId={} reason={}", event.reservationId(), event.reason());
        reservationService.handlePaymentFailed(event.reservationId());
    }
}
