package com.xrail.notification.kafka;

import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.*;
import com.xrail.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * N4: 6개 토픽 구독. 각 이벤트를 알림으로 변환.
 * N1: 멱등 처리는 NotificationService가 담당.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = Topics.USER_SIGNED_UP, groupId = "notification-service")
    public void onUserSignedUp(UserSignedUpEvent event) {
        log.info("Received user.signed-up userId={}", event.userId());
        notificationService.dispatch(
                event.userId(),
                "WELCOME",
                event.eventId(),
                Map.of("name", event.name(), "email", event.email())
        );
    }

    @KafkaListener(topics = Topics.RESERVATION_CREATED, groupId = "notification-service")
    public void onReservationCreated(ReservationCreatedEvent event) {
        log.info("Received reservation.created reservationId={}", event.reservationId());
        notificationService.dispatch(
                event.userId(),
                "RESERVATION_CREATED",
                event.eventId(),
                Map.of(
                        "reservationId", event.reservationId(),
                        "userName", event.userName(),
                        "totalPrice", event.totalPrice(),
                        "expiresAt", event.expiresAt()
                )
        );
    }

    @KafkaListener(topics = Topics.PAYMENT_COMPLETED, groupId = "notification-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received payment.completed paymentId={}", event.paymentId());
        notificationService.dispatch(
                event.userId(),
                "PAYMENT_COMPLETED",
                event.eventId(),
                Map.of(
                        "paymentId", event.paymentId(),
                        "reservationId", event.reservationId(),
                        "amount", event.amount(),
                        "providerTxnId", event.providerTxnId() != null ? event.providerTxnId() : ""
                )
        );
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = "notification-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received payment.failed paymentId={}", event.paymentId());
        notificationService.dispatch(
                event.userId(),
                "PAYMENT_FAILED",
                event.eventId(),
                Map.of(
                        "paymentId", event.paymentId(),
                        "reservationId", event.reservationId(),
                        "amount", event.amount(),
                        "reason", event.reason() != null ? event.reason() : ""
                )
        );
    }

    @KafkaListener(topics = Topics.SEAT_CONFIRMED, groupId = "notification-service")
    public void onSeatConfirmed(SeatConfirmedEvent event) {
        log.info("Received seat.confirmed reservationId={}", event.reservationId());
        notificationService.dispatch(
                event.userId(),
                "PAYMENT_COMPLETED",  // seat.confirmed → 결제 완료 알림과 동일 (티켓 발급)
                event.eventId(),
                Map.of("reservationId", event.reservationId(), "ticketIds", event.ticketIds())
        );
    }

    @KafkaListener(topics = Topics.SEAT_RELEASED, groupId = "notification-service")
    public void onSeatReleased(SeatReleasedEvent event) {
        // N4: reason에 따라 템플릿 분기
        String template = resolveTemplate(event.reason());
        if (template == null) {
            log.debug("Skipping seat.released notification for reason={}", event.reason());
            return;
        }

        Long userId = event.userId();
        if (userId == null) {
            log.warn("seat.released event has no userId, skipping notification reservationId={}", event.reservationId());
            return;
        }

        log.info("Received seat.released reservationId={} reason={}", event.reservationId(), event.reason());
        notificationService.dispatch(
                userId,
                template,
                event.eventId(),
                Map.of(
                        "reservationId", event.reservationId() != null ? event.reservationId() : 0L,
                        "scheduleId", event.scheduleId() != null ? event.scheduleId() : 0L,
                        "reason", event.reason()
                )
        );
    }

    /** N4: reason → template 분기 */
    private String resolveTemplate(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case "TIMEOUT" -> "SEAT_RELEASED_TIMEOUT";
            case "PAYMENT_FAILED" -> "PAYMENT_FAILED";
            case "USER_CANCELLED" -> "SEAT_RELEASED_TIMEOUT"; // 취소 알림 (1차: 같은 템플릿)
            default -> null; // RECONCILE 등은 알림 불필요
        };
    }
}
