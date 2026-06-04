package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.dto.ReservationStatsResponse;
import com.xrail.train.entity.DltAlertLog;
import com.xrail.train.entity.ReservationSagaLog;
import com.xrail.train.entity.enums.ReservationStatus;
import com.xrail.train.repository.DltAlertLogRepository;
import com.xrail.train.service.ReservationService;
import com.xrail.train.service.SagaLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "운영자 전용 — DLT 알림·예약 모니터링·Saga 로그·강제 취소 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DltAlertLogRepository dltAlertLogRepository;
    private final ReservationService reservationService;
    private final SagaLogService sagaLogService;

    @Operation(summary = "DLT 알림 목록 조회", description = "Dead Letter Topic에 격리된 메시지 이력 조회. topic 파라미터로 필터링 가능.")
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

    @Operation(summary = "예약 모니터링 통계", description = "상태별 예약 건수와 결제 완료 매출 집계.")
    @GetMapping("/reservations/stats")
    public ApiResponse<ReservationStatsResponse> reservationStats(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole) {
        requireAdmin(userRole);
        return ApiResponse.ok(reservationService.stats());
    }

    @Operation(summary = "예약 목록 조회", description = "전체 예약을 페이지로 조회. status 파라미터(PENDING/PAID/CANCELLED)로 필터링 가능.")
    @GetMapping("/reservations")
    public ApiResponse<Page<ReservationResponse>> listReservations(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ReservationStatus status) {
        requireAdmin(userRole);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(reservationService.adminSearch(status, pageRequest));
    }

    @Operation(summary = "예약 단건 조회", description = "예약 ID로 상세 조회 (소유권 검증 없음).")
    @GetMapping("/reservations/{reservationId}")
    public ApiResponse<ReservationResponse> getReservation(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @PathVariable Long reservationId) {
        requireAdmin(userRole);
        return ApiResponse.ok(reservationService.adminGetById(reservationId));
    }

    @Operation(summary = "예약 강제 취소", description = "운영자가 임의 예약을 강제 취소하고 좌석을 반환합니다.")
    @PostMapping("/reservations/{reservationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelReservation(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @PathVariable Long reservationId) {
        requireAdmin(userRole);
        reservationService.cancelByAdmin(reservationId);
    }

    @Operation(summary = "Saga 로그 조회", description = "예약 보상 이벤트 흐름 추적 로그. reservationId 파라미터로 필터링 가능.")
    @GetMapping("/saga-logs")
    public ApiResponse<Page<ReservationSagaLog>> listSagaLogs(
            @RequestHeader(value = Headers.USER_ROLE, defaultValue = "") String userRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long reservationId) {
        requireAdmin(userRole);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("observedAt").descending());
        return ApiResponse.ok(sagaLogService.findLogs(reservationId, pageRequest));
    }

    private void requireAdmin(String userRole) {
        if (!"ADMIN".equals(userRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
