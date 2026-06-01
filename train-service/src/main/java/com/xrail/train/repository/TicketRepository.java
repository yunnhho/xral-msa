package com.xrail.train.repository;

import com.xrail.train.entity.Ticket;
import com.xrail.train.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByReservationReservationId(Long reservationId);

    // Bug #4 fix: status 필터 포함 — CANCELLED 티켓은 충돌에서 제외
    boolean existsByScheduleIdAndSeatIdAndStartStationIdxLessThanAndEndStationIdxGreaterThanAndStatusIn(
            Long scheduleId, Long seatId, int endIdx, int startIdx, Collection<TicketStatus> statuses);

    List<Ticket> findByScheduleIdAndSeatIdAndStatusIn(Long scheduleId, Long seatId, Collection<TicketStatus> statuses);

    // Bug #2 fix: 서비스 재시작 후 Redis 복원용 — 활성 티켓 전체 조회
    List<Ticket> findByStatusIn(Collection<TicketStatus> statuses);
}
