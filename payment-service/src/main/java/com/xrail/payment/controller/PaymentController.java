package com.xrail.payment.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.payment.dto.PaymentRequest;
import com.xrail.payment.dto.PaymentResponse;
import com.xrail.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Payment", description = "결제 요청·조회 API")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 요청",
               description = "Idempotency-Key 헤더 필수. Redisson 분산 락 → Redis 상태 확인 → Mock PG 호출 → DB 기록 순서. " +
                             "동일 key 재요청 시 기존 결과 반환(멱등).")
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> pay(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestHeader(value = Headers.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        PaymentResponse response = paymentService.pay(userId, request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "결제 단건 조회", description = "결제 ID로 내 결제 내역 상세 조회.")
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @RequestHeader(Headers.USER_ID) Long userId,
            @PathVariable Long paymentId) {

        PaymentResponse response = paymentService.getById(paymentId, userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
