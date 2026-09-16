package com.jobportal.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// Replaces the application's Clock bean with a fixed instant for every integration test
// (Section 12.1), so seeded "days ago" data and any @Transactional test changes always
// land on the same date: 16 Sep 2026, 10:00 IST.
@TestConfiguration
public class FixedClockConfig {

    // The method name MUST differ from ClockConfig#clock: same name = bean definition
    // override, not a second bean, and Spring Boot's default
    // spring.main.allow-bean-definition-overriding=false would fail the whole context.
    // @Primary resolves the ambiguity between the two differently-named beans instead.
    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-16T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }
}
