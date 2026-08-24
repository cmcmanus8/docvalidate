package com.docvalidate.service.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** Injected rather than called statically so tests can pin time without sleeping. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
