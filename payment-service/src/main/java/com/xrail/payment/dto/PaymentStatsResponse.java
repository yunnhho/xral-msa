package com.xrail.payment.dto;

/**
 * 운영자 결제 모니터링 집계.
 * revenue = 결제 완료(COMPLETED) 청구액 합계, refundedAmount = 환불(CANCELLED) 청구액 합계.
 */
public record PaymentStatsResponse(
        long total,
        long requested,
        long completed,
        long failed,
        long cancelled,
        long revenue,
        long refundedAmount
) {}
