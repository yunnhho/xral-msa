package com.xrail.payment.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.payment.dto.PaymentRequest;
import com.xrail.payment.dto.PaymentResponse;
import com.xrail.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> pay(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestHeader(value = Headers.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        PaymentResponse response = paymentService.pay(userId, request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @RequestHeader(Headers.USER_ID) Long userId,
            @PathVariable Long paymentId) {

        PaymentResponse response = paymentService.getById(paymentId, userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
