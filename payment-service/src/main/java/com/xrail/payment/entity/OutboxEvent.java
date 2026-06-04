package com.xrail.payment.entity;

import com.xrail.common.entity.BaseTimeEntity;
import com.xrail.payment.entity.enums.OutboxStatus;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Transactional Outbox (P4 해소): 비즈니스 트랜잭션과 동일 트랜잭션에서 INSERT되어
 * DB 커밋과 이벤트 발행의 원자성을 보장한다. 발행은 relay 스케줄러가 담당한다.
 */
@Getter
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    protected OutboxEvent() {}

    @Builder
    public OutboxEvent(String aggregateType, String aggregateId, String topic,
                       String eventKey, String eventType, String eventId, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.eventId = eventId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }
}
