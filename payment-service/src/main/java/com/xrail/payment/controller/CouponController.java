package com.xrail.payment.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.payment.dto.CouponValidateResponse;
import com.xrail.payment.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Coupon", description = "쿠폰 검증 API")
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "쿠폰 검증", description = "쿠폰 코드와 결제 금액을 입력하면 할인 금액을 반환합니다.")
    @GetMapping("/validate")
    public ApiResponse<CouponValidateResponse> validate(
            @RequestParam String code,
            @RequestParam long amount) {
        return ApiResponse.ok(couponService.validate(code, amount));
    }
}
