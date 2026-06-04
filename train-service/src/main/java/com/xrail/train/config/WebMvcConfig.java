package com.xrail.train.config;

import com.xrail.train.interceptor.IdempotencyInterceptor;
import com.xrail.train.interceptor.QueueTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final QueueTokenInterceptor queueTokenInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // T2: 등록 순서 = 실행 순서 — QueueToken(인증) → Idempotency(중복방지) → service
        // 큐 토큰: 예약 생성(POST) + 좌석 조회(GET .../seats) 보호. 메서드별 분기는 인터셉터 내부에서 처리.
        registry.addInterceptor(queueTokenInterceptor)
                .addPathPatterns("/api/reservations", "/api/schedules/*/seats");
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/reservations");
    }
}
