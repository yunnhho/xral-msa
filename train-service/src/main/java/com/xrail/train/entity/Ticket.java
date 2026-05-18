package com.xrail.train.entity;

import com.xrail.common.entity.BaseTimeEntity;
import com.xrail.train.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "tickets")
public class Ticket extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id")
    private Long ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "start_station_id", nullable = false)
    private Long startStationId;

    @Column(name = "end_station_id", nullable = false)
    private Long endStationId;

    @Column(name = "start_station_idx", nullable = false)
    private int startStationIdx;

    @Column(name = "end_station_idx", nullable = false)
    private int endStationIdx;

    @Column(nullable = false)
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    protected Ticket() {}

    @Builder
    public Ticket(Reservation reservation, Long scheduleId, Long seatId,
                  Long startStationId, Long endStationId,
                  int startStationIdx, int endStationIdx, Long price) {
        this.reservation = reservation;
        this.scheduleId = scheduleId;
        this.seatId = seatId;
        this.startStationId = startStationId;
        this.endStationId = endStationId;
        this.startStationIdx = startStationIdx;
        this.endStationIdx = endStationIdx;
        this.price = price;
        this.status = TicketStatus.RESERVED;
    }

    public void issue() {
        this.status = TicketStatus.ISSUED;
    }

    public void cancel() {
        this.status = TicketStatus.CANCELLED;
    }
}
