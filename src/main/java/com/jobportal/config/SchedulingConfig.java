package com.jobportal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on Spring's @Scheduled processing for JobSweepScheduler (service package,
// Section 7.11). A one-line class of its own, the same minimal shape as ClockConfig, so
// the whole scheduled-sweep feature - this switch, the scheduler and the service it
// triggers - is easy to find, and to remove as one unit if it is ever revisited.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
