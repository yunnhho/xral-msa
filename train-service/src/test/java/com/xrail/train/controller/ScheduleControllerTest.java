package com.xrail.train.controller;

import com.xrail.train.exception.TrainExceptionHandler;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.TicketRepository;
import com.xrail.train.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ScheduleController.class)
@Import(TrainExceptionHandler.class)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScheduleService scheduleService;

    // Interceptors loaded by WebMvcConfig need these beans
    @MockBean private RedissonClient redissonClient;
    @MockBean private ReservationRepository reservationRepository;
    @MockBean private TicketRepository ticketRepository;
    @MockBean private SeatRepository seatRepository;

    // @EnableJpaAuditing requires JPA metamodel
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void sameStation_returns400_invalidRoute() throws Exception {
        mockMvc.perform(get("/api/schedules")
                        .param("departureStationId", "1")
                        .param("arrivalStationId", "1")
                        .param("date", "2026-06-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROUTE"));
    }

    @Test
    void differentStations_returns200() throws Exception {
        when(scheduleService.search(anyLong(), anyLong(), any(LocalDate.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/schedules")
                        .param("departureStationId", "1")
                        .param("arrivalStationId", "2")
                        .param("date", "2026-06-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedules").isArray());
    }

    @Test
    void missingRequiredParam_returns5xx() throws Exception {
        // MissingServletRequestParameterException falls through to Exception handler (→ 500).
        // GlobalExceptionHandlerSupport.handleException() catches all Exceptions.
        mockMvc.perform(get("/api/schedules")
                        .param("departureStationId", "1")
                        .param("arrivalStationId", "2"))
                .andExpect(status().is5xxServerError());
    }
}
