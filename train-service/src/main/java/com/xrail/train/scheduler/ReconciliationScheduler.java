package com.xrail.train.scheduler;

import com.xrail.train.entity.Ticket;
import com.xrail.train.entity.enums.TicketStatus;
import com.xrail.train.kafka.TrainEventProducer;
import com.xrail.train.repository.TicketRepository;
import com.xrail.train.service.LuaScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    // 최대 비트 위치 (노선 최대 역 수 가정)
    private static final int MAX_BIT_POSITIONS = 64;

    private final LuaScriptService luaScriptService;
    private final TicketRepository ticketRepository;
    private final TrainEventProducer eventProducer;
    private final RedissonClient redissonClient;

    // T4: 5분 주기. DB가 source of truth — Redis bitmask를 DB에 맞게 교정
    @Scheduled(fixedDelay = 300_000)
    public void reconcile() {
        log.info("Starting Redis-DB bitmask reconciliation");
        int phantomKeyCount = 0;

        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern("sch:*:seat:*");
        for (String key : keys) {
            try {
                boolean hadPhantom = processKey(key);
                if (hadPhantom) phantomKeyCount++;
            } catch (Exception e) {
                log.error("Reconciliation error for key={}", key, e);
            }
        }

        log.info("Reconciliation complete. Keys with phantom bits cleared: {}", phantomKeyCount);
    }

    private boolean processKey(String key) {
        // 키 파싱: sch:{scheduleId}:seat:{seatId}
        String[] parts = key.split(":");
        if (parts.length != 4) return false;

        long scheduleId;
        long seatId;
        try {
            scheduleId = Long.parseLong(parts[1]);
            seatId = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return false;
        }

        // DB에서 활성 티켓 조회 (RESERVED or ISSUED — 이 비트들은 정당함)
        List<Ticket> activeTickets = ticketRepository.findByScheduleIdAndSeatIdAndStatusIn(
                scheduleId, seatId, List.of(TicketStatus.RESERVED, TicketStatus.ISSUED));

        boolean hadPhantom = false;

        for (int bit = 0; bit < MAX_BIT_POSITIONS; bit++) {
            if (!redissonClient.getBitSet(key).get(bit)) continue;

            // DB 활성 티켓이 이 비트를 커버하는지 확인
            final int b = bit;
            boolean covered = activeTickets.stream()
                    .anyMatch(t -> b >= t.getStartStationIdx() && b < t.getEndStationIdx());

            if (!covered) {
                // 팬텀 비트 — DB에 근거 없음. T1 규칙: rollback_seat.lua로만 해제
                luaScriptService.rollback(scheduleId, seatId, bit, bit + 1);
                hadPhantom = true;
                log.warn("Cleared phantom bit key={} bit={}", key, bit);
            }
        }

        if (hadPhantom) {
            eventProducer.publishSeatReleasedReconcile(scheduleId, seatId);
        }

        return hadPhantom;
    }
}
