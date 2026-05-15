package com.xrail.common.kafka;

public final class Topics {

    private Topics() {}

    public static final String USER_SIGNED_UP          = "user.signed-up";
    public static final String RESERVATION_CREATED     = "reservation.created";
    public static final String SEAT_LOCKED             = "seat.locked";
    public static final String SEAT_LOCK_FAILED        = "seat.lock-failed";
    public static final String PAYMENT_REQUESTED       = "payment.requested";
    public static final String PAYMENT_COMPLETED       = "payment.completed";
    public static final String PAYMENT_COMPLETED_DLT   = "payment.completed.DLT";
    public static final String PAYMENT_FAILED          = "payment.failed";
    public static final String SEAT_CONFIRMED          = "seat.confirmed";
    public static final String SEAT_RELEASED           = "seat.released";
    public static final String NOTIFICATION_DISPATCHED = "notification.dispatched";
}
