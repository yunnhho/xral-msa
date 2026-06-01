package com.xrail.train.service;

import com.xrail.train.dto.ScheduleResponse;
import com.xrail.train.entity.RouteStation;
import com.xrail.train.entity.Seat;
import com.xrail.train.entity.Schedule;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final long BASE_PRICE_PER_SEGMENT = 10_000L;

    private final ScheduleRepository scheduleRepository;
    private final RouteStationRepository routeStationRepository;
    private final SeatRepository seatRepository;
    private final LuaScriptService luaScriptService;

    @Transactional(readOnly = true)
    public List<ScheduleResponse> search(Long departureStationId, Long arrivalStationId, LocalDate date) {
        List<Long> routeIds = routeStationRepository.findRouteIdsByDepartureAndArrival(departureStationId, arrivalStationId);
        if (routeIds.isEmpty()) {
            return List.of();
        }
        return scheduleRepository.findByDepartureDateAndRouteRouteIdIn(date, routeIds)
                .stream()
                .map(s -> buildResponse(s, departureStationId, arrivalStationId))
                .toList();
    }

    private ScheduleResponse buildResponse(Schedule s, Long departureStationId, Long arrivalStationId) {
        Long routeId = s.getRoute().getRouteId();

        RouteStation depRS = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, departureStationId)
                .orElseThrow();
        RouteStation arrRS = routeStationRepository
                .findByRouteRouteIdAndStationStationId(routeId, arrivalStationId)
                .orElseThrow();

        int startIdx = depRS.getStationSequence();
        int endIdx = arrRS.getStationSequence();

        List<Seat> seats = seatRepository.findAllByTrainId(s.getTrain().getTrainId());
        int availableSeats = (int) seats.stream()
                .filter(seat -> luaScriptService.isFree(s.getScheduleId(), seat.getSeatId(), startIdx, endIdx))
                .count();

        long estimatedPrice = BASE_PRICE_PER_SEGMENT * (endIdx - startIdx);

        String duration = formatDuration(s.getDepartureTime(), s.getArrivalTime());

        return new ScheduleResponse(
                s.getScheduleId(),
                s.getTrain().getTrainId(),
                s.getTrain().getTrainNumber(),
                s.getTrain().getTrainType().name(),
                routeId,
                s.getRoute().getName(),
                s.getDepartureDate(),
                s.getDepartureTime(),
                s.getArrivalTime(),
                new ScheduleResponse.StationInfo(depRS.getStation().getStationId(), depRS.getStation().getName()),
                new ScheduleResponse.StationInfo(arrRS.getStation().getStationId(), arrRS.getStation().getName()),
                duration,
                estimatedPrice,
                availableSeats
        );
    }

    private String formatDuration(LocalTime dep, LocalTime arr) {
        Duration d = Duration.between(dep, arr);
        if (d.isNegative()) d = d.plusHours(24);
        long h = d.toHours();
        long m = d.toMinutesPart();
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}
