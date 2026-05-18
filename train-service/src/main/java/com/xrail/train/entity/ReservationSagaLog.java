package com.xrail.train.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reservation_saga_log")
@EntityListeners(AuditingEntityListener.class)
public class ReservationSagaLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @CreatedDate
    @Column(name = "observed_at", nullable = false, updatable = false)
    private LocalDateTime observedAt;

    protected ReservationSagaLog() {}

    @Builder
    public ReservationSagaLog(Long reservationId, String eventType, String direction, String payloadJson) {
        this.reservationId = reservationId;
        this.eventType = eventType;
        this.direction = direction;
        this.payloadJson = payloadJson;
    }
}
