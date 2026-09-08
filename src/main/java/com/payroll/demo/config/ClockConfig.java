package com.payroll.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injected rather than called statically so scheduled-period selection is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
