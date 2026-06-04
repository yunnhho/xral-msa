package com.xrail.payment.pg;

public interface PaymentGateway {
    PgResult charge(Long paymentId, Long amount, String method);

    PgResult refund(Long paymentId, Long amount);

    record PgResult(boolean success, String providerTxnId, String failureReason) {}
}
