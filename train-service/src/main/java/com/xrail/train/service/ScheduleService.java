package com.xrail.train.service;

import com.xrail.train.dto.ScheduleResponse;
import com.xrail.train.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public List<ScheduleResponse> search(Long routeId, LocalDate departureDate) {
        return scheduleRepository.findByDepartureDateAndRouteRouteId(departureDate, routeId)
                .stream()
                .map(ScheduleResponse::from)
                .toList();
    }
}
