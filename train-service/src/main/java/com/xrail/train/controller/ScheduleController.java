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

@Tag(name = "Schedule", description = "열차 스케줄 조회 API")
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @Operation(summary = "스케줄 검색", description = "routeId + date(YYYY-MM-DD)로 해당 날짜의 운행 스케줄 목록 조회.")
    @GetMapping
    public ApiResponse<List<ScheduleResponse>> search(
            @RequestParam Long routeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(scheduleService.search(routeId, date));
    }
}
