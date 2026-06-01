package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.train.entity.Station;
import com.xrail.train.repository.StationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Station", description = "역 목록 조회 API")
@RestController
@RequestMapping("/api/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationRepository stationRepository;

    @Operation(summary = "전체 역 목록 조회", description = "등록된 모든 역 이름 목록 반환.")
    @GetMapping
    public ApiResponse<List<Station>> getAll() {
        return ApiResponse.ok(stationRepository.findAll());
    }
}
