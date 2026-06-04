package com.xrail.train.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.train.entity.ReservationSagaLog;
import com.xrail.train.repository.ReservationSagaLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaLogService {

    private final ReservationSagaLogRepository sagaLogRepository;
    private final ObjectMapper objectMapper;

    // timeout=3: saga log is non-critical; fail fast rather than wait innodb_lock_wait_timeout (50s default)
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void recordOutbound(Long reservationId, String eventType, Object payload) {
        save(reservationId, eventType, "OUTBOUND", payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public void recordInbound(Long reservationId, String eventType, Object payload) {
        save(reservationId, eventType, "INBOUND", payload);
    }

    // 운영자 디버깅용 — saga 이벤트 흐름 조회 (reservationId 로 필터 가능)
    @Transactional(readOnly = true)
    public Page<ReservationSagaLog> findLogs(Long reservationId, Pageable pageable) {
        return (reservationId != null)
                ? sagaLogRepository.findByReservationId(reservationId, pageable)
                : sagaLogRepository.findAll(pageable);
    }

    private void save(Long reservationId, String eventType, String direction, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            json = "{\"error\":\"serialization failed\"}";
            log.warn("Saga log serialization failed reservationId={}", reservationId, e);
        }
        sagaLogRepository.save(ReservationSagaLog.builder()
                .reservationId(reservationId)
                .eventType(eventType)
                .direction(direction)
                .payloadJson(json)
                .build());
    }
}
