package com.xrail.train.entity;

import com.xrail.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
@Table(name = "route_stations")
public class RouteStation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "route_station_id")
    private Long routeStationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(name = "station_sequence", nullable = false)
    private int stationSequence;

    @Column(name = "cumulative_distance", nullable = false)
    private double cumulativeDistance;
}
