package com.xrail.train.repository;

import com.xrail.train.entity.RouteStation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteStationRepository extends JpaRepository<RouteStation, Long> {

    @EntityGraph(attributePaths = {"station"})
    List<RouteStation> findByRouteRouteIdOrderByStationSequenceAsc(Long routeId);

    @EntityGraph(attributePaths = {"station"})
    Optional<RouteStation> findByRouteRouteIdAndStationStationId(Long routeId, Long stationId);

    @Query("SELECT rs FROM RouteStation rs JOIN FETCH rs.route r JOIN FETCH rs.station s " +
           "WHERE r.routeId = :routeId AND rs.stationSequence = :seq")
    Optional<RouteStation> findByRouteIdAndSeq(Long routeId, int seq);

    @Query("SELECT DISTINCT rs1.route.routeId FROM RouteStation rs1, RouteStation rs2 " +
           "WHERE rs1.route.routeId = rs2.route.routeId " +
           "AND rs1.station.stationId = :depId " +
           "AND rs2.station.stationId = :arrId " +
           "AND rs1.stationSequence < rs2.stationSequence")
    List<Long> findRouteIdsByDepartureAndArrival(@Param("depId") Long depId, @Param("arrId") Long arrId);
}
