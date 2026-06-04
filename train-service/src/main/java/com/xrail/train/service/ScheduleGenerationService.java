package com.xrail.train.service;

import com.xrail.train.entity.Route;
import com.xrail.train.entity.RouteStation;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Train;
import com.xrail.train.repository.RouteRepository;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 예매 가능 창(오늘 ~ 한 달 후)에 대한 스케줄을 생성한다.
 * - 부팅 시 창 전체를 백필하고, 매일 00시 스케줄러가 새로 창에 들어오는 날(+1개월)을 생성한다.
 * - (route, date) 단위 멱등: 이미 스케줄이 있으면 건너뛴다(재기동/중복 실행 안전).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleGenerationService {

    /** 예매 가능 범위: 오늘부터 한 달 후까지. */
    public static final int BOOKING_WINDOW_DAYS = 30;
    private static final int FIRST_HOUR = 6;
    private static final int LAST_HOUR = 22;
    private static final int HOUR_STEP = 2;
    private static final double AVG_SPEED_KMH = 200.0;

    private final RouteRepository routeRepository;
    private final TrainRepository trainRepository;
    private final RouteStationRepository routeStationRepository;
    private final ScheduleRepository scheduleRepository;

    /** 부팅 시 호출: 오늘부터 BOOKING_WINDOW_DAYS일까지 누락분을 채운다. */
    @Transactional
    public int ensureBookingWindow() {
        LocalDate today = LocalDate.now();
        int total = 0;
        for (int i = 0; i <= BOOKING_WINDOW_DAYS; i++) {
            total += generateForDate(today.plusDays(i));
        }
        if (total > 0) {
            log.info("Booking window backfill 완료: {}건 생성 (오늘~+{}일)", total, BOOKING_WINDOW_DAYS);
        }
        return total;
    }

    /**
     * 특정 날짜의 모든 노선 스케줄을 생성한다. 이미 존재하는 (route, date)는 건너뛴다.
     * @return 생성된 스케줄 수
     */
    @Transactional
    public int generateForDate(LocalDate date) {
        List<Route> routes = routeRepository.findAll();
        List<Train> trains = trainRepository.findAll();
        if (routes.isEmpty() || trains.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (Route route : routes) {
            // 멱등: 해당 노선·날짜 스케줄이 이미 있으면 skip
            if (!scheduleRepository.findByDepartureDateAndRouteRouteId(date, route.getRouteId()).isEmpty()) {
                continue;
            }

            List<RouteStation> stations = routeStationRepository
                    .findByRouteRouteIdOrderByStationSequenceAsc(route.getRouteId());
            if (stations.size() < 2) {
                continue;
            }
            double totalDistance = stations.get(stations.size() - 1).getCumulativeDistance();
            int travelMinutes = (int) (totalDistance / AVG_SPEED_KMH * 60.0);

            for (int hour = FIRST_HOUR; hour <= LAST_HOUR; hour += HOUR_STEP) {
                // 날짜·시간에 따라 열차를 분산 배정 (참고 시스템 동일 방식)
                Train train = trains.get((date.getDayOfMonth() + hour) % trains.size());
                LocalTime departure = LocalTime.of(hour, 0);
                scheduleRepository.save(Schedule.builder()
                        .route(route)
                        .train(train)
                        .departureDate(date)
                        .departureTime(departure)
                        .arrivalTime(departure.plusMinutes(travelMinutes))
                        .build());
                created++;
            }
        }
        return created;
    }
}
