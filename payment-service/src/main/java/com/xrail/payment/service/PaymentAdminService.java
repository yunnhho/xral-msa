package com.xrail.payment.service;

import com.xrail.payment.dto.OutboxStatusResponse;
import com.xrail.payment.dto.PaymentStatsResponse;
import com.xrail.payment.entity.OutboxEvent;
import com.xrail.payment.entity.enums.OutboxStatus;
import com.xrail.payment.entity.enums.PaymentStatus;
import com.xrail.payment.repository.OutboxEventRepository;
import com.xrail.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaymentAdminService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxRepository;

    @Transactional(readOnly = true)
    public PaymentStatsResponse stats() {
        long requested = paymentRepository.countByStatus(PaymentStatus.REQUESTED);
        long completed = paymentRepository.countByStatus(PaymentStatus.COMPLETED);
        long failed = paymentRepository.countByStatus(PaymentStatus.FAILED);
        long cancelled = paymentRepository.countByStatus(PaymentStatus.CANCELLED);
        long revenue = paymentRepository.sumChargedAmountByStatus(PaymentStatus.COMPLETED);
        long refundedAmount = paymentRepository.sumChargedAmountByStatus(PaymentStatus.CANCELLED);
        return new PaymentStatsResponse(
                requested + completed + failed + cancelled,
                requested, completed, failed, cancelled, revenue, refundedAmount);
    }

    @Transactional(readOnly = true)
    public OutboxStatusResponse outboxStatus() {
        long pending = outboxRepository.countByStatus(OutboxStatus.PENDING);
        long sent = outboxRepository.countByStatus(OutboxStatus.SENT);
        Long oldestAge = outboxRepository.findFirstByStatusOrderByIdAsc(OutboxStatus.PENDING)
                .map(OutboxEvent::getCreatedAt)
                .map(created -> Duration.between(created, LocalDateTime.now()).getSeconds())
                .orElse(null);
        return new OutboxStatusResponse(pending, sent, oldestAge);
    }
}
