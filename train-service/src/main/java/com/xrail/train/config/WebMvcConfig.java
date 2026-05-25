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
        registry.addInterceptor(queueTokenInterceptor)
                .addPathPatterns("/api/reservations");
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/reservations");
    }
}
