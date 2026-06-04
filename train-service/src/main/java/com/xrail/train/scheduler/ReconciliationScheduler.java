package com.xrail.train.scheduler;

import com.xrail.train.entity.Ticket;
import com.xrail.train.entity.enums.TicketStatus;
import com.xrail.train.kafka.TrainEventProducer;
import com.xrail.train.repository.TicketRepository;
import com.xrail.train.service.LuaScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private static final int MAX_BIT_POSITIONS = 64;

    private final LuaScriptService luaScriptService;
    private final TicketRepository ticketRepository;
    private final TrainEventProducer eventProducer;
    private final RedissonClient redissonClient;

    // Phantom 비트는 연속 2회 reconcile에서 모두 phantom일 때만 제거한다.
    // 직전 사이클에 set되었으나 아직 커밋되지 않은 in-flight 예약의 비트를
    // 한 사이클만에 phantom으로 오인해 제거하는 race를 방지한다.
    private Set<String> previousPhantomCandidates = new HashSet<>();

    // Bug #2 fix: 서비스 기동 시 DB → Redis 복원 (재시작 후 bitmask 초기화 대응)
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        log.info("Restoring Redis bitmask from DB on startup...");
        try {
            List<Ticket> active = ticketRepository.findByStatusIn(
                    List.of(TicketStatus.RESERVED, TicketStatus.ISSUED));
            int restored = 0;
            for (Ticket t : active) {
                try {
                    boolean wasSet = luaScriptService.tryReserve(
                            t.getScheduleId(), t.getSeatId(),
                            t.getStartStationIdx(), t.getEndStationIdx());
                    if (!wasSet) {
                        // 이미 set된 경우 (정상) — 무시
                    } else {
                        restored++;
                    }
                } catch (Exception e) {
                    log.error("Failed to restore bitmask for ticketId={}", t.getTicketId(), e);
                }
            }
            log.info("Startup restore complete. Restored {} bitmask entries from {} active tickets.",
                    restored, active.size());
        } catch (Exception e) {
            log.error("Startup bitmask restore failed", e);
        }
    }

    // T4: 5분 주기. DB가 source of truth — Redis bitmask를 DB에 맞게 양방향 교정
    @Scheduled(fixedDelay = 300_000)
    public void reconcile() {
        log.info("Starting Redis-DB bitmask reconciliation");
        int phantomCleared = 0;
        int missingRestored = 0;

        // 1단계: phantom 비트 제거 (Redis에 있지만 DB에 없는 비트).
        // 연속 2회 phantom으로 확인된 비트만 제거 (in-flight 예약 보호).
        Set<String> currentPhantomCandidates = new HashSet<>();
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern("sch:*:seat:*");
        for (String key : keys) {
            try {
                boolean hadPhantom = removePhantomBits(key, currentPhantomCandidates);
                if (hadPhantom) phantomCleared++;
            } catch (Exception e) {
                log.error("Reconciliation phantom-clear error for key={}", key, e);
            }
        }
        previousPhantomCandidates = currentPhantomCandidates;

        // 2단계: 누락 비트 복원 (DB에 있지만 Redis에 없는 비트) — Bug #2 fix
        try {
            List<Ticket> active = ticketRepository.findByStatusIn(
                    List.of(TicketStatus.RESERVED, TicketStatus.ISSUED));
            for (Ticket t : active) {
                try {
                    boolean wasFree = luaScriptService.isFree(
                            t.getScheduleId(), t.getSeatId(),
                            t.getStartStationIdx(), t.getEndStationIdx());
                    if (wasFree) {
                        // DB에 RESERVED/ISSUED 티켓이 있는데 Redis bitmask가 없음 → 복원
                        luaScriptService.tryReserve(
                                t.getScheduleId(), t.getSeatId(),
                                t.getStartStationIdx(), t.getEndStationIdx());
                        missingRestored++;
                        log.warn("Restored missing bitmask ticketId={} scheduleId={} seatId={}",
                                t.getTicketId(), t.getScheduleId(), t.getSeatId());
                    }
                } catch (Exception e) {
                    log.error("Failed to restore missing bit for ticketId={}", t.getTicketId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Reconciliation restore step failed", e);
        }

        log.info("Reconciliation complete. Phantom cleared: {}, Missing restored: {}",
                phantomCleared, missingRestored);
    }

    private boolean removePhantomBits(String key, Set<String> currentPhantomCandidates) {
        String[] parts = key.split(":");
        if (parts.length != 4) return false;

        long scheduleId, seatId;
        try {
            scheduleId = Long.parseLong(parts[1]);
            seatId = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return false;
        }

        List<Ticket> activeTickets = ticketRepository.findByScheduleIdAndSeatIdAndStatusIn(
                scheduleId, seatId, List.of(TicketStatus.RESERVED, TicketStatus.ISSUED));

        boolean hadPhantom = false;
        for (int bit = 0; bit < MAX_BIT_POSITIONS; bit++) {
            if (!redissonClient.getBitSet(key).get(bit)) continue;
            final int b = bit;
            boolean covered = activeTickets.stream()
                    .anyMatch(t -> b >= t.getStartStationIdx() && b < t.getEndStationIdx());
            if (covered) continue;

            String candidateId = key + "#" + bit;
            currentPhantomCandidates.add(candidateId);
            if (!previousPhantomCandidates.contains(candidateId)) {
                // 첫 관측 — in-flight 예약일 수 있으므로 이번 사이클엔 제거하지 않음
                log.debug("Phantom candidate (deferred) key={} bit={}", key, bit);
                continue;
            }
            luaScriptService.rollback(scheduleId, seatId, bit, bit + 1);
            hadPhantom = true;
            log.warn("Cleared phantom bit key={} bit={}", key, bit);
        }
        if (hadPhantom) eventProducer.publishSeatReleasedReconcile(scheduleId, seatId);
        return hadPhantom;
    }
}
