package com.xrail.train.scheduler;

import com.xrail.train.service.ScheduleGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 예매 가능 창(오늘 ~ +1개월) 유지:
 * - 부팅 직후: 창 전체 백필(누락분 생성).
 * - 매일 00:00: 새로 창에 진입하는 날(오늘+BOOKING_WINDOW_DAYS) 스케줄 생성.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleGenerationScheduler {

    private final ScheduleGenerationService generationService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            generationService.ensureBookingWindow();
        } catch (Exception e) {
            log.error("부팅 시 예매 창 백필 실패", e);
        }
    }

    @Scheduled(cron = "${train.schedule.daily-generation-cron:0 0 0 * * *}")
    public void generateNewlyOpenedDay() {
        LocalDate target = LocalDate.now().plusDays(ScheduleGenerationService.BOOKING_WINDOW_DAYS);
        try {
            int created = generationService.generateForDate(target);
            log.info("일일 스케줄 생성: date={} created={}", target, created);
        } catch (Exception e) {
            log.error("일일 스케줄 생성 실패 date={}", target, e);
        }
    }
}
