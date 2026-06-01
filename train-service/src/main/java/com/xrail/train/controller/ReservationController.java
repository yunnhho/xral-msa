package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.train.dto.ReservationRequest;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Reservation", description = "예약 생성·조회·취소 API")
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "예약 생성",
               description = "Queue-Token 검증 → Idempotency 중복 체크 → Lua 비트마스크 좌석 잠금 → DB INSERT 순서. " +
                             "Idempotency-Key 헤더 권장.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> create(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestHeader(Headers.USER_NAME) String userName,
            @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReservationRequest request) {
        return ApiResponse.ok(reservationService.create(userId, userName, request, idempotencyKey));
    }

    @Operation(summary = "내 예약 목록 조회", description = "로그인 사용자의 전체 예약 목록 반환. page/size 파라미터는 무시됨 (전체 반환).")
    @GetMapping
    public ApiResponse<List<ReservationResponse>> listMine(
            @RequestHeader(Headers.USER_ID) Long userId) {
        return ApiResponse.ok(reservationService.listByUser(userId));
    }

    @Operation(summary = "예약 단건 조회", description = "예약 ID로 내 예약 상세 조회.")
    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationResponse> getById(
            @RequestHeader(Headers.USER_ID) Long userId,
            @PathVariable Long reservationId) {
        return ApiResponse.ok(reservationService.getById(reservationId, userId));
    }

    @Operation(summary = "예약 취소", description = "예약을 취소하고 좌석을 반환합니다.")
    @DeleteMapping("/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @RequestHeader(Headers.USER_ID) Long userId,
            @PathVariable Long reservationId) {
        reservationService.cancelByUser(reservationId, userId);
    }
}
