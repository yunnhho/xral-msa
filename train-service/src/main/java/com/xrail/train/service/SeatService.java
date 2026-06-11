package com.xrail.train.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.train.dto.SeatsResponse;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Seat;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SeatService {

    private static final long BASE_PRICE_PER_SEGMENT = 10_000L;

    private final ScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final RouteStationRepository routeStationRepository;
    private final LuaScriptService luaScriptService;

    @Transactional(readOnly = true)
    public SeatsResponse getAvailability(Long scheduleId, Long departureStationId, Long arrivalStationId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        Long routeId = schedule.getRoute().getRouteId();

        int startIdx = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, departureStationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_IN_ROUTE))
                .getStationSequence();

        int endIdx = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, arrivalStationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_IN_ROUTE))
                .getStationSequence();

        long pricePerSeat = BASE_PRICE_PER_SEGMENT * (endIdx - startIdx);

        List<Seat> seats = seatRepository.findAllByTrainId(schedule.getTrain().getTrainId());

        // 좌석 가용 여부는 Redis 왕복 1회로 배치 조회
        List<Long> seatIds = seats.stream().map(Seat::getSeatId).toList();
        List<Boolean> freeFlags = luaScriptService.areFree(scheduleId, seatIds, startIdx, endIdx);
        Map<Long, Boolean> freeBySeatId = new LinkedHashMap<>();
        for (int i = 0; i < seats.size(); i++) {
            freeBySeatId.put(seats.get(i).getSeatId(), freeFlags.get(i));
        }

        // Group seats by carriage preserving insertion order
        Map<Long, List<Seat>> byCarriage = new LinkedHashMap<>();
        for (Seat seat : seats) {
            byCarriage.computeIfAbsent(seat.getCarriage().getCarriageId(), id -> new ArrayList<>()).add(seat);
        }

        List<SeatsResponse.CarriageDto> carriages = byCarriage.entrySet().stream()
                .map(entry -> {
                    Seat first = entry.getValue().get(0);
                    List<SeatsResponse.SeatDto> seatDtos = entry.getValue().stream()
                            .map(s -> new SeatsResponse.SeatDto(
                                    s.getSeatId(),
                                    s.getSeatNumber(),
                                    freeBySeatId.get(s.getSeatId()),
                                    pricePerSeat
                            ))
                            .toList();
                    return new SeatsResponse.CarriageDto(
                            entry.getKey(),
                            first.getCarriage().getCarriageNumber(),
                            seatDtos
                    );
                })
                .toList();

        return new SeatsResponse(scheduleId, new SeatsResponse.Segment(startIdx, endIdx), carriages);
    }
}
