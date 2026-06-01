package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.train.dto.SeatsResponse;
import com.xrail.train.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Seat", description = "좌석 가용 현황 조회 API")
@RestController
@RequestMapping("/api/schedules/{scheduleId}/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @Operation(summary = "좌석 가용 현황 조회",
               description = "특정 스케줄의 구간(departureStationId~arrivalStationId)별 좌석 점유 여부 조회. Redis Lua 비트마스크 기반.")
    @GetMapping
    public ApiResponse<SeatsResponse> getAvailability(
            @PathVariable Long scheduleId,
            @RequestParam Long departureStationId,
            @RequestParam Long arrivalStationId) {
        return ApiResponse.ok(seatService.getAvailability(scheduleId, departureStationId, arrivalStationId));
    }
}
