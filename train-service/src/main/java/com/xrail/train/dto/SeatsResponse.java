package com.xrail.train.dto;

import java.util.List;

public record SeatsResponse(
        Long scheduleId,
        Segment segment,
        List<CarriageDto> carriages
) {
    public record Segment(int startIdx, int endIdx) {}

    public record CarriageDto(
            Long carriageId,
            int carriageNumber,
            List<SeatDto> seats
    ) {}

    public record SeatDto(
            Long seatId,
            String seatNumber,
            boolean available,
            long price
    ) {}
}
