package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.train.entity.DltAlertLog;
import com.xrail.train.repository.DltAlertLogRepository;
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

@Tag(name = "Admin", description = "DLT 알림 관리 API (운영자 전용)")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DltAlertLogRepository dltAlertLogRepository;

    @Operation(summary = "DLT 알림 목록 조회", description = "Dead Letter Topic에 격리된 메시지 이력 조회. topic 파라미터로 필터링 가능.")
    @GetMapping("/dlt-alerts")
    public ApiResponse<Page<DltAlertLog>> listDltAlerts(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String topic) {
        if (!"ADMIN".equals(userRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<DltAlertLog> result = (topic != null)
                ? dltAlertLogRepository.findByTopic(topic, pageRequest)
                : dltAlertLogRepository.findAll(pageRequest);
        return ApiResponse.ok(result);
    }
}
