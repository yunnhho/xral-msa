package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.train.dto.ScheduleResponse;
import com.xrail.train.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Schedule", description = "열차 스케줄 조회 API")
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @Operation(summary = "스케줄 검색", description = "출발역/도착역/날짜로 운행 스케줄 목록 조회.")
    @GetMapping
    public ApiResponse<Map<String, List<ScheduleResponse>>> search(
            @RequestParam Long departureStationId,
            @RequestParam Long arrivalStationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ScheduleResponse> schedules = scheduleService.search(departureStationId, arrivalStationId, date);
        return ApiResponse.ok(Map.of("schedules", schedules));
    }
}
