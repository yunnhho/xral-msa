package com.xrail.train.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.train.dto.SeatAvailabilityResponse;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Seat;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.RouteStationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final RouteStationRepository routeStationRepository;
    private final LuaScriptService luaScriptService;

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResponse> getAvailability(Long scheduleId, int startIdx, int endIdx) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        List<Seat> seats = seatRepository.findAllByTrainId(schedule.getTrain().getTrainId());
        return seats.stream()
                .map(seat -> new SeatAvailabilityResponse(
                        seat.getSeatId(),
                        seat.getSeatNumber(),
                        seat.getCarriage().getCarriageNumber(),
                        luaScriptService.isFree(scheduleId, seat.getSeatId(), startIdx, endIdx)
                ))
                .toList();
    }
}
