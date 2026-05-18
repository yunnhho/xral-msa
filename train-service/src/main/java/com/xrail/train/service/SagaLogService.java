package com.xrail.train.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.train.entity.ReservationSagaLog;
import com.xrail.train.repository.ReservationSagaLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaLogService {

    private final ReservationSagaLogRepository sagaLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutbound(Long reservationId, String eventType, Object payload) {
        save(reservationId, eventType, "OUTBOUND", payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInbound(Long reservationId, String eventType, Object payload) {
        save(reservationId, eventType, "INBOUND", payload);
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
