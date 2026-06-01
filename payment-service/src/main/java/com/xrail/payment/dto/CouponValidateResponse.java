package com.xrail.payment.dto;

public record CouponValidateResponse(
        String code,
        String type,        // "PERCENT" | "FIXED"
        long discountValue, // 10 (= 10%) or 5000 (= 5000원)
        long discountAmount,// 실제 할인 금액 (원)
        long finalAmount,   // 최종 결제 금액
        String description
) {}
