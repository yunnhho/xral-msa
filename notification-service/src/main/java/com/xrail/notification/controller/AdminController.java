package com.xrail.notification.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.common.security.Roles;
import com.xrail.notification.dto.NotificationStatsResponse;
import com.xrail.notification.entity.DltAlertLog;
import com.xrail.notification.repository.DltAlertLogRepository;
import com.xrail.notification.repository.NotificationLogRepository;
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

@Tag(name = "Admin", description = "알림 운영자 모니터링 API (발송 통계·DLT)")
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminController {

    private final DltAlertLogRepository dltAlertLogRepository;
    private final NotificationLogRepository notificationLogRepository;

    @Operation(summary = "알림 발송 통계", description = "상태별(SENT/PENDING/FAILED) 알림 로그 건수 집계.")
    @GetMapping("/stats")
    public ApiResponse<NotificationStatsResponse> stats(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole) {
        requireAdmin(userRole);
        long sent = notificationLogRepository.countByStatus("SENT");
        long pending = notificationLogRepository.countByStatus("PENDING");
        long failed = notificationLogRepository.countByStatus("FAILED");
        return ApiResponse.ok(new NotificationStatsResponse(sent + pending + failed, sent, pending, failed));
    }

    @Operation(summary = "DLT 알림 목록", description = "Dead Letter Topic에 격리된 메시지 이력.")
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
