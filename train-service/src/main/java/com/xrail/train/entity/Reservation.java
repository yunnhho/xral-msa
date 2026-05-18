package com.xrail.train.entity;

import com.xrail.common.entity.BaseTimeEntity;
import com.xrail.train.entity.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reservations")
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_name", length = 50)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected Reservation() {}

    @Builder
    public Reservation(Long userId, String userName, Long totalPrice,
                       String idempotencyKey, LocalDateTime reservedAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.userName = userName;
        this.status = ReservationStatus.PENDING;
        this.totalPrice = totalPrice;
        this.idempotencyKey = idempotencyKey;
        this.reservedAt = reservedAt;
        this.expiresAt = expiresAt;
    }

    public void markPaid() {
        this.status = ReservationStatus.PAID;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
