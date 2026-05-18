package com.xrail.train.repository;

import com.xrail.train.entity.RouteStation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RouteStationRepository extends JpaRepository<RouteStation, Long> {

    @EntityGraph(attributePaths = {"station"})
    List<RouteStation> findByRouteRouteIdOrderByStationSequenceAsc(Long routeId);

    Optional<RouteStation> findByRouteRouteIdAndStationStationId(Long routeId, Long stationId);

    @Query("SELECT rs FROM RouteStation rs JOIN FETCH rs.route r JOIN FETCH rs.station s " +
           "WHERE r.routeId = :routeId AND rs.stationSequence = :seq")
    Optional<RouteStation> findByRouteIdAndSeq(Long routeId, int seq);
}
