package com.xrail.train.controller;

import com.xrail.common.header.Headers;
import com.xrail.train.dto.ReservationStatsResponse;
import com.xrail.train.exception.TrainExceptionHandler;
import com.xrail.train.repository.DltAlertLogRepository;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.TicketRepository;
import com.xrail.train.service.ReservationService;
import com.xrail.train.service.SagaLogService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import(TrainExceptionHandler.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private DltAlertLogRepository dltAlertLogRepository;
    @MockBean private ReservationService reservationService;
    @MockBean private SagaLogService sagaLogService;

    // Interceptors loaded by WebMvcConfig need these beans
    @MockBean private RedissonClient redissonClient;
    @MockBean private ReservationRepository reservationRepository;
    @MockBean private TicketRepository ticketRepository;
    @MockBean private SeatRepository seatRepository;

    @MockBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void reservationStats_nonAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/reservations/stats")
                        .header(Headers.USER_ROLE, "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void reservationStats_admin_returnsStats() throws Exception {
        when(reservationService.stats())
                .thenReturn(new ReservationStatsResponse(10, 3, 5, 2, 150_000));

        mockMvc.perform(get("/api/admin/reservations/stats")
                        .header(Headers.USER_ROLE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.paid").value(5))
                .andExpect(jsonPath("$.data.paidRevenue").value(150_000));
    }

    @Test
    void listSagaLogs_admin_returnsPage() throws Exception {
        Page<?> empty = new PageImpl<>(List.of());
        when(sagaLogService.findLogs(any(), any())).thenReturn((Page) empty);

        mockMvc.perform(get("/api/admin/saga-logs")
                        .header(Headers.USER_ROLE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void cancelReservation_admin_invokesServiceAndReturns204() throws Exception {
        mockMvc.perform(post("/api/admin/reservations/{id}/cancel", 7L)
                        .header(Headers.USER_ROLE, "ADMIN"))
                .andExpect(status().isNoContent());

        verify(reservationService).cancelByAdmin(eq(7L));
    }

    @Test
    void cancelReservation_nonAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/admin/reservations/{id}/cancel", 7L)
                        .header(Headers.USER_ROLE, "USER"))
                .andExpect(status().isForbidden());
    }
}
