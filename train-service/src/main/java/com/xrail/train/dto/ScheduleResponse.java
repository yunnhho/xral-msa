package com.xrail.train.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleResponse(
        Long scheduleId,
        Long trainId,
        String trainNumber,
        String trainType,
        Long routeId,
        String routeName,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        StationInfo departureStation,
        StationInfo arrivalStation,
        String duration,
        long estimatedPrice,
        int availableSeats
) {
    public record StationInfo(Long stationId, String name) {}
}
