package com.jobportal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Thin @Scheduled trigger over JobSweepService.sweep() (Section 7.11). Every line that
// changes data lives in the service, so JobSweepServiceTest calls sweep() directly with
// the fixed test Clock and never waits on this timer (Section 12.1: no test may depend on
// wall-clock timing). This class itself is covered by JobSweepSchedulerTest, which builds
// it by hand with a mocked JobSweepService and calls the trigger method directly - the
// same "no Spring context, no timer" shape FileStorageServiceTest already uses.
//
// Excluded from the application context entirely (not merely left with nothing to do)
// whenever app.job-sweep.enabled=false - see application-test.properties, which sets
// exactly that for every test. Without this, the bean would still be created in the one
// Spring context IntegrationTestBase caches and shares across dozens of test classes
// (Section 12.1), and a real background tick landing between two unrelated test methods
// could close the seeded "Python Backend Developer" job (Section 13.4) - relied on by
// name, as an Expired job, by AC-P2-4, AC-DE-1, AC-E-F1-3, AC-E-D1-1, AC-E-D4-4 and
// several seeker-side ACs - or "Frontend Developer" once its seeded HIRED application
// fills its one opening (13.5 A6), out from under a test that never called this feature.
@Component
@ConditionalOnProperty(prefix = "app.job-sweep", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobSweepScheduler {

    private final JobSweepService jobSweepService;

    public JobSweepScheduler(JobSweepService jobSweepService) {
        this.jobSweepService = jobSweepService;
    }

    // Interval configurable via application.properties (Section 10.1); 1 hour if the
    // property is ever missing. initialDelay is deliberately the same as the interval,
    // not 0: reset-demo.bat re-seeds "Python Backend Developer" as already Expired
    // specifically so the Expired badge, the E-D4 history tab and the dashboard's "Needs
    // attention" row have something to show (13.4, 15.5 demo script); firing a sweep at
    // t=0 would race that against whoever starts the app for a demo or the manual
    // pre-demo checklist (15.4), closing it before anyone gets to see it as Expired.
    @Scheduled(fixedDelayString = "${app.job-sweep.interval-ms:3600000}",
            initialDelayString = "${app.job-sweep.interval-ms:3600000}")
    public void sweepExpiredAndFilledJobs() {
        jobSweepService.sweep();
    }
}
