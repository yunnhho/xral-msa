package com.xrail.train.dto;

/**
 * 운영자 모니터링용 예약 집계.
 * paidRevenue 는 결제 완료(PAID) 예약의 총 매출.
 */
public record ReservationStatsResponse(
        long total,
        long pending,
        long paid,
        long cancelled,
        long paidRevenue
) {}
