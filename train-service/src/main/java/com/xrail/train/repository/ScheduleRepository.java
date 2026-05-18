package com.xrail.train.repository;

import com.xrail.train.entity.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    @EntityGraph(attributePaths = {"route", "train"})
    List<Schedule> findByRoutRouteIdAndDepartureDate(Long routeId, LocalDate departureDate);

    @EntityGraph(attributePaths = {"route", "train"})
    List<Schedule> findByDepartureDateAndRouteRouteId(LocalDate departureDate, Long routeId);
}
