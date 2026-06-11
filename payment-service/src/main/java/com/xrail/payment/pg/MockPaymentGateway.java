package com.xrail.payment.pg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Value("${payment.mock.always-fail:false}")
    private boolean alwaysFail;

    // 랜덤 실패율 (기본 10%). 부하 테스트 등 결정적 동작이 필요하면 0으로 설정.
    @Value("${payment.mock.failure-rate:0.1}")
    private double failureRate;

    @Override
    public PgResult charge(Long paymentId, Long amount, String method) {
        // P3: always-fail 토글로 실패 시나리오 강제
        boolean success = !alwaysFail && Math.random() >= failureRate;
        if (success) {
            String txnId = "MOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            log.info("MockPG success paymentId={} txnId={}", paymentId, txnId);
            return new PgResult(true, txnId, null);
        }
        log.info("MockPG failure paymentId={}", paymentId);
        return new PgResult(false, null, "MOCK_FAILURE");
    }

    @Override
    public PgResult refund(Long paymentId, Long amount) {
        // Mock: 환불은 항상 성공한다고 가정 (실 PG에서도 환불 실패는 드물다).
        String txnId = "MOCK-RF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        log.info("MockPG refund success paymentId={} amount={} txnId={}", paymentId, amount, txnId);
        return new PgResult(true, txnId, null);
    }
}
