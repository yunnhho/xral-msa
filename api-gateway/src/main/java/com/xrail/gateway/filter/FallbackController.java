package com.xrail.gateway.filter;

import com.xrail.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/auth-service")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> authFallback() {
        return Mono.just(ApiResponse.error("AUTH_SERVICE_UNAVAILABLE", "인증 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }

    @RequestMapping("/fallback/train-service")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> trainFallback() {
        return Mono.just(ApiResponse.error("TRAIN_SERVICE_UNAVAILABLE", "열차 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }

    @RequestMapping("/fallback/queue-service")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> queueFallback() {
        return Mono.just(ApiResponse.error("QUEUE_SERVICE_UNAVAILABLE", "대기열 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }

    @RequestMapping("/fallback/payment-service")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> paymentFallback() {
        return Mono.just(ApiResponse.error("PAYMENT_SERVICE_UNAVAILABLE", "결제 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }

    @RequestMapping("/fallback/notification-service")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> notificationFallback() {
        return Mono.just(ApiResponse.error("NOTIFICATION_SERVICE_UNAVAILABLE", "알림 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }
}
