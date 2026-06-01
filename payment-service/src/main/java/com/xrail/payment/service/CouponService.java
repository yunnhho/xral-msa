package com.xrail.payment.service;

import com.xrail.common.exception.BusinessException;
import com.xrail.common.exception.ErrorCode;
import com.xrail.payment.dto.CouponValidateResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CouponService {

    record CouponDef(String type, long value, String description) {}

    // 테스트용 쿠폰 정의 (실제 서비스에서는 DB/Redis 관리)
    private static final Map<String, CouponDef> COUPONS = Map.of(
        "XRAIL10",    new CouponDef("PERCENT", 10,   "10% 할인 쿠폰"),
        "SUMMER5000", new CouponDef("FIXED",   5000, "여름 특가 5,000원 할인"),
        "WELCOME3000",new CouponDef("FIXED",   3000, "첫 예매 3,000원 할인"),
        "KTX20",      new CouponDef("PERCENT", 20,   "KTX 특별 20% 할인")
    );

    public CouponValidateResponse validate(String code, long amount) {
        CouponDef def = COUPONS.get(code.toUpperCase());
        if (def == null) throw new BusinessException(ErrorCode.COUPON_INVALID);

        long discount = switch (def.type()) {
            case "PERCENT" -> amount * def.value() / 100;
            case "FIXED"   -> Math.min(def.value(), amount);
            default        -> 0L;
        };

        long finalAmount = Math.max(0, amount - discount);
        return new CouponValidateResponse(code.toUpperCase(), def.type(), def.value(), discount, finalAmount, def.description());
    }

    public long applyDiscount(String code, long amount) {
        if (code == null || code.isBlank()) return amount;
        CouponDef def = COUPONS.get(code.toUpperCase());
        if (def == null) return amount;
        long discount = switch (def.type()) {
            case "PERCENT" -> amount * def.value() / 100;
            case "FIXED"   -> Math.min(def.value(), amount);
            default        -> 0L;
        };
        return Math.max(0, amount - discount);
    }
}
