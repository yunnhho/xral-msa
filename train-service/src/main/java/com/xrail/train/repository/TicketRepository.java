package com.xrail.train.repository;

import com.xrail.train.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByReservationReservationId(Long reservationId);

    boolean existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThan(
            Long scheduleId, Long seatId, int endIdx, int startIdx);
}
