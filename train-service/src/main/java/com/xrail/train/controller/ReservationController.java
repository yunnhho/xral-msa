package com.xrail.train.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.train.dto.ReservationRequest;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> create(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestHeader(Headers.USER_NAME) String userName,
            @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ReservationRequest request) {
        return ApiResponse.ok(reservationService.create(userId, userName, request, idempotencyKey));
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationResponse> getById(
            @RequestHeader(Headers.USER_ID) Long userId,
            @PathVariable Long reservationId) {
        return ApiResponse.ok(reservationService.getById(reservationId, userId));
    }

    @GetMapping("/me")
    public ApiResponse<List<ReservationResponse>> listMine(
            @RequestHeader(Headers.USER_ID) Long userId) {
        return ApiResponse.ok(reservationService.listByUser(userId));
    }
}
