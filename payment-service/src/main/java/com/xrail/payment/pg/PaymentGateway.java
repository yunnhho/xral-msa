package com.xrail.payment.pg;

public interface PaymentGateway {
    PgResult charge(Long paymentId, Long amount, String method);

    record PgResult(boolean success, String providerTxnId, String failureReason) {}
}
