package com.xrail.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    // 기본 12. 부하 테스트 등 CPU 제약 환경에서는 낮출 수 있으나 A1 규칙상 하한 10을 강제한다.
    @Value("${auth.bcrypt.strength:12}")
    private int strength;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(Math.max(strength, 10));
    }
}
