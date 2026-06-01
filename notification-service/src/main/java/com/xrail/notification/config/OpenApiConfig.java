package com.xrail.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("XRail Notification Service API")
                        .description("사용자 알림 로그 조회. Kafka 이벤트 수신 → INAPP/SMS/EMAIL/PUSH 전송.")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("gatewayAuth"))
                .components(new Components()
                        .addSecuritySchemes("gatewayAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-User-Id")
                                .description("Gateway가 주입하는 사용자 ID 헤더")));
    }
}
