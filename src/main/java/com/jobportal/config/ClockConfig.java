package com.jobportal.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The single source of "now" for the whole application (Section 7.10). Every service
// calls LocalDateTime.now(clock) / LocalDate.now(clock) instead of the static now(), so
// tests can swap in a fixed clock (FixedClockConfig) and get deterministic dates.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
