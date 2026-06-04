package com.xrail.payment.repository;

import com.xrail.payment.entity.Payment;
import com.xrail.payment.entity.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findFirstByReservationIdAndStatus(Long reservationId, PaymentStatus status);

    long countByStatus(PaymentStatus status);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(p.amount - p.discountAmount), 0) FROM Payment p WHERE p.status = :status")
    long sumChargedAmountByStatus(PaymentStatus status);
}
