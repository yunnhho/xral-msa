package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.train.entity.Station;
import com.xrail.train.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationRepository stationRepository;

    @GetMapping
    public ApiResponse<List<Station>> getAll() {
        return ApiResponse.ok(stationRepository.findAll());
    }
}
