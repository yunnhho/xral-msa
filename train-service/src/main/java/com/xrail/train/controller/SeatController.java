package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.train.dto.SeatAvailabilityResponse;
import com.xrail.train.service.SeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schedules/{scheduleId}/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping
    public ApiResponse<List<SeatAvailabilityResponse>> getAvailability(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "0") int startStationIdx,
            @RequestParam(defaultValue = "1") int endStationIdx) {
        return ApiResponse.ok(seatService.getAvailability(scheduleId, startStationIdx, endStationIdx));
    }
}
