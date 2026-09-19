package com.jobportal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Thin @Scheduled trigger over JobAlertService.sendDueDigests() - the exact same split
// JobSweepScheduler already establishes for the deadline/openings sweep (Section 7.11):
// every line that changes data or decides "is this due" lives in the service, so
// JobAlertServiceTest calls sendDueDigests() directly with the fixed test Clock and never
// waits on this timer. This class itself is covered by JobAlertSchedulerTest, built by hand
// with a mocked JobAlertService and called directly - no Spring context, no timer, the same
// shape JobSweepSchedulerTest already uses.
//
// check-interval-ms is deliberately a much SMALLER number than the digest itself is
// promised to arrive (app.job-alerts.digest-frequency-days, read by JobAlertService, not
// here): this is how often the app bothers to ASK "is anyone due", not how often any one
// seeker actually hears from it - see JobAlertService's own class comment for why that gap
// matters (a seeker with nothing to report this week is checked again on the very next
// tick, not made to wait out a fixed week once something finally does match).
//
// Excluded from the application context entirely whenever app.job-alerts.enabled=false -
// see application-test.properties, which sets exactly that for every test, for the same
// reason JobSweepScheduler is excluded there: a real background tick landing between two
// unrelated test methods could email a digest built from the shared seed data out from
// under a test that never called this feature, and every test that DOES exercise it calls
// JobAlertService directly instead.
@Component
@ConditionalOnProperty(prefix = "app.job-alerts", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobAlertScheduler {

    private final JobAlertService jobAlertService;

    public JobAlertScheduler(JobAlertService jobAlertService) {
        this.jobAlertService = jobAlertService;
    }

    @Scheduled(fixedDelayString = "${app.job-alerts.check-interval-ms:3600000}",
            initialDelayString = "${app.job-alerts.check-interval-ms:3600000}")
    public void sendDueJobAlertDigests() {
        jobAlertService.sendDueDigests();
    }
}
