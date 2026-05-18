package com.xrail.train.dto;

import com.xrail.train.entity.Schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleResponse(
        Long scheduleId,
        Long routeId,
        String routeName,
        String trainNumber,
        String trainType,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime
) {
    public static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(
                s.getScheduleId(),
                s.getRoute().getRouteId(),
                s.getRoute().getName(),
                s.getTrain().getTrainNumber(),
                s.getTrain().getTrainType().name(),
                s.getDepartureDate(),
                s.getDepartureTime(),
                s.getArrivalTime()
        );
    }
}
