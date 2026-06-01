package com.xrail.train.config;

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
                        .title("XRail Train Service API")
                        .description("노선·스케줄 조회, 예약 생성/조회, 좌석 가용 현황")
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
