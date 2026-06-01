package com.xrail.queue.config;

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
                        .title("XRail Queue Service API")
                        .description("대기열 진입·상태 조회·SSE 구독·이탈. Queue-Token 발급.")
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
