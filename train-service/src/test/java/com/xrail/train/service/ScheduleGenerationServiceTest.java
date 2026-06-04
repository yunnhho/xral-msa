package com.xrail.train.service;

import com.xrail.train.entity.Route;
import com.xrail.train.entity.RouteStation;
import com.xrail.train.entity.Schedule;
import com.xrail.train.entity.Train;
import com.xrail.train.repository.RouteRepository;
import com.xrail.train.repository.RouteStationRepository;
import com.xrail.train.repository.ScheduleRepository;
import com.xrail.train.repository.TrainRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleGenerationServiceTest {

    @Mock private RouteRepository routeRepository;
    @Mock private TrainRepository trainRepository;
    @Mock private RouteStationRepository routeStationRepository;
    @Mock private ScheduleRepository scheduleRepository;

    @InjectMocks
    private ScheduleGenerationService service;

    private void stubRouteAndTrain() {
        Route route = mock(Route.class);
        lenient().when(route.getRouteId()).thenReturn(1L);
        Train train = mock(Train.class);
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(trainRepository.findAll()).thenReturn(List.of(train));

        RouteStation first = mock(RouteStation.class);
        lenient().when(first.getCumulativeDistance()).thenReturn(0.0);
        RouteStation last = mock(RouteStation.class);
        lenient().when(last.getCumulativeDistance()).thenReturn(400.0);
        lenient().when(routeStationRepository.findByRouteRouteIdOrderByStationSequenceAsc(1L))
                .thenReturn(List.of(first, last));
    }

    @Test
    void generateForDate_createsOnePerTimeSlot() {
        stubRouteAndTrain();
        when(scheduleRepository.findByDepartureDateAndRouteRouteId(any(), anyLong()))
                .thenReturn(List.of());

        int created = service.generateForDate(LocalDate.of(2026, 7, 1));

        // 06,08,10,12,14,16,18,20,22시 = 9개
        assertThat(created).isEqualTo(9);
        verify(scheduleRepository, org.mockito.Mockito.times(9)).save(any(Schedule.class));
    }

    @Test
    void generateForDate_skipsWhenAlreadyExists() {
        stubRouteAndTrain();
        when(scheduleRepository.findByDepartureDateAndRouteRouteId(any(), anyLong()))
                .thenReturn(List.of(mock(Schedule.class))); // 이미 존재

        int created = service.generateForDate(LocalDate.of(2026, 7, 1));

        assertThat(created).isZero();
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void generateForDate_noTrains_returnsZero() {
        when(routeRepository.findAll()).thenReturn(List.of(mock(Route.class)));
        when(trainRepository.findAll()).thenReturn(List.of());

        int created = service.generateForDate(LocalDate.of(2026, 7, 1));

        assertThat(created).isZero();
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void ensureBookingWindow_coversWholeWindow() {
        stubRouteAndTrain();
        when(scheduleRepository.findByDepartureDateAndRouteRouteId(any(), anyLong()))
                .thenReturn(List.of());

        int total = service.ensureBookingWindow();

        // (창 일수+1)일 × 9슬롯
        assertThat(total).isEqualTo((ScheduleGenerationService.BOOKING_WINDOW_DAYS + 1) * 9);
    }
}
