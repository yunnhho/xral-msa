package com.xrail.queue.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.common.security.Roles;
import com.xrail.queue.dto.QueueModeRequest;
import com.xrail.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "QueueAdmin", description = "운영자 대기열 입장 제어(모드 조회/변경) API")
@RestController
@RequestMapping("/api/admin/queue")
@RequiredArgsConstructor
public class QueueAdminController {

    private final QueueService queueService;

    @Operation(summary = "대기열 모드 조회",
               description = "현재 입장 제어 모드(AUTO/FORCE_ON/FORCE_OFF) + 임계치 + scope별 대기/active 현황.")
    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> getMode(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole) {
        requireAdmin(userRole);
        return ApiResponse.ok(queueService.modeSnapshot());
    }

    @Operation(summary = "대기열 모드 변경",
               description = "AUTO(평시 우회+임계치 자동 대기) / FORCE_ON(항상 대기) / FORCE_OFF(대기열 비활성).")
    @PutMapping("/mode")
    public ApiResponse<Map<String, Object>> setMode(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @Valid @RequestBody QueueModeRequest request) {
        requireAdmin(userRole);
        queueService.setMode(request.mode());
        return ApiResponse.ok(queueService.modeSnapshot());
    }

    private void requireAdmin(String userRole) {
        if (!Roles.ADMIN.equals(userRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
