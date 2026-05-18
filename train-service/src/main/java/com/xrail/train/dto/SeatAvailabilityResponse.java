package com.xrail.train.dto;

public record SeatAvailabilityResponse(
        Long seatId,
        String seatNumber,
        int carriageNumber,
        boolean available
) {}
