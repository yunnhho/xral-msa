package com.xrail.payment.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.common.security.Roles;
import com.xrail.payment.dto.OutboxStatusResponse;
import com.xrail.payment.dto.PaymentStatsResponse;
import com.xrail.payment.entity.DltAlertLog;
import com.xrail.payment.repository.DltAlertLogRepository;
import com.xrail.payment.service.PaymentAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "결제 운영자 모니터링 API (통계·Outbox·DLT)")
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminController {

    private final DltAlertLogRepository dltAlertLogRepository;
    private final PaymentAdminService paymentAdminService;

    @Operation(summary = "결제 통계", description = "상태별 결제 건수 + 매출(COMPLETED) + 환불액(CANCELLED) 집계.")
    @GetMapping("/stats")
    public ApiResponse<PaymentStatsResponse> stats(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole) {
        requireAdmin(userRole);
        return ApiResponse.ok(paymentAdminService.stats());
    }

    @Operation(summary = "Outbox 상태", description = "Transactional Outbox의 PENDING/SENT 수와 가장 오래된 미발행 이벤트 경과시간(초).")
    @GetMapping("/outbox")
    public ApiResponse<OutboxStatusResponse> outbox(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole) {
        requireAdmin(userRole);
        return ApiResponse.ok(paymentAdminService.outboxStatus());
    }

    @Operation(summary = "DLT 알림 목록", description = "Dead Letter Topic에 격리된 결제/환불 메시지 이력.")
    @GetMapping("/dlt-alerts")
    public ApiResponse<Page<DltAlertLog>> listDltAlerts(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String topic) {
        requireAdmin(userRole);

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<DltAlertLog> result = (topic != null)
                ? dltAlertLogRepository.findByTopic(topic, pageRequest)
                : dltAlertLogRepository.findAll(pageRequest);
        return ApiResponse.ok(result);
    }

    private void requireAdmin(String userRole) {
        if (!Roles.ADMIN.equals(userRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
