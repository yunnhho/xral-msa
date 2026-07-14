package com.xrail.gateway.filter;

import com.xrail.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    private static final Map<String, String> SERVICE_LABELS = Map.of(
            "auth-service", "인증",
            "train-service", "열차",
            "queue-service", "대기열",
            "payment-service", "결제",
            "notification-service", "알림");

    @RequestMapping("/fallback/{service}")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ApiResponse<Void>> fallback(@PathVariable String service) {
        String code = service.replace('-', '_').toUpperCase() + "_UNAVAILABLE";
        String label = SERVICE_LABELS.getOrDefault(service, service);
        return Mono.just(ApiResponse.error(code, label + " 서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요."));
    }
}
