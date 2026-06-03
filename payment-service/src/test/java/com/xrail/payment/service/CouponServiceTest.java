package com.xrail.payment.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.payment.dto.CouponValidateResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponServiceTest {

    private final CouponService couponService = new CouponService();

    // ===== Case-insensitive lookup (B-NEW-4) =====

    @Test
    void uppercaseCode_validates() {
        CouponValidateResponse response = couponService.validate("XRAIL10", 50_000L);
        assertThat(response.code()).isEqualTo("XRAIL10");
    }

    @Test
    void lowercaseCode_validatesViaToUpperCase() {
        CouponValidateResponse response = couponService.validate("xrail10", 50_000L);
        assertThat(response.code()).isEqualTo("XRAIL10");
    }

    @Test
    void mixedCaseCode_validates() {
        CouponValidateResponse response = couponService.validate("Xrail10", 50_000L);
        assertThat(response.code()).isEqualTo("XRAIL10");
    }

    @Test
    void unknownCode_throwsCouponInvalid() {
        assertThatThrownBy(() -> couponService.validate("BADCODE", 50_000L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COUPON_INVALID));
    }

    // ===== PERCENT coupon =====

    @Test
    void percentCoupon_correctDiscount() {
        // XRAIL10 = 10% off
        CouponValidateResponse response = couponService.validate("XRAIL10", 50_000L);

        assertThat(response.discountAmount()).isEqualTo(5_000L);  // 10% of 50000
        assertThat(response.finalAmount()).isEqualTo(45_000L);
    }

    @Test
    void percentCoupon_ktx20_correctDiscount() {
        // KTX20 = 20% off
        CouponValidateResponse response = couponService.validate("KTX20", 40_000L);

        assertThat(response.discountAmount()).isEqualTo(8_000L);
        assertThat(response.finalAmount()).isEqualTo(32_000L);
    }

    // ===== FIXED coupon =====

    @Test
    void fixedCoupon_correctDiscount() {
        // SUMMER5000 = 5000 off
        CouponValidateResponse response = couponService.validate("SUMMER5000", 30_000L);

        assertThat(response.discountAmount()).isEqualTo(5_000L);
        assertThat(response.finalAmount()).isEqualTo(25_000L);
    }

    @Test
    void fixedCoupon_discountExceedsAmount_finalAmountZero() {
        // SUMMER5000 = 5000 off, but amount is only 3000
        CouponValidateResponse response = couponService.validate("SUMMER5000", 3_000L);

        assertThat(response.discountAmount()).isEqualTo(3_000L); // capped at amount
        assertThat(response.finalAmount()).isEqualTo(0L);
    }

    // ===== applyDiscount =====

    @Test
    void applyDiscount_nullCode_returnsOriginalAmount() {
        long result = couponService.applyDiscount(null, 50_000L);
        assertThat(result).isEqualTo(50_000L);
    }

    @Test
    void applyDiscount_blankCode_returnsOriginalAmount() {
        long result = couponService.applyDiscount("  ", 50_000L);
        assertThat(result).isEqualTo(50_000L);
    }

    @Test
    void applyDiscount_invalidCode_returnsOriginalAmount() {
        long result = couponService.applyDiscount("INVALID", 50_000L);
        assertThat(result).isEqualTo(50_000L);
    }

    @Test
    void applyDiscount_validCode_returnsDiscountedAmount() {
        long result = couponService.applyDiscount("XRAIL10", 50_000L);
        assertThat(result).isEqualTo(45_000L);
    }
}
