package com.xrail.notification.controller;

import com.xrail.notification.entity.DltAlertLog;
import com.xrail.notification.exception.NotificationExceptionHandler;
import com.xrail.notification.repository.DltAlertLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import(NotificationExceptionHandler.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DltAlertLogRepository dltAlertLogRepository;

    // @EnableJpaAuditing requires JPA metamodel — mock it for WebMvcTest slice
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void adminRole_returns200() throws Exception {
        when(dltAlertLogRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/admin/dlt-alerts")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void memberRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/dlt-alerts")
                        .header("X-User-Role", "ROLE_MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void noRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/dlt-alerts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminRole_withTopicFilter_returnsFiltered() throws Exception {
        when(dltAlertLogRepository.findByTopic(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/admin/dlt-alerts")
                        .header("X-User-Role", "ADMIN")
                        .param("topic", "reservation.created.DLT"))
                .andExpect(status().isOk());
    }
}
