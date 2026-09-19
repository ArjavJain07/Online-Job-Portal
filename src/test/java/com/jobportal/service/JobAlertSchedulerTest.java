package com.jobportal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;

// Unit test for the @Scheduled trigger itself (Section 16 future-work item 5), built by
// hand with a mocked JobAlertService instead of a Spring context or a running scheduler -
// the exact same shape JobSweepSchedulerTest already uses for JobSweepScheduler, and for
// the same reason: no test may wait on a real timer (Section 12.1). Proves the trigger
// really is "thin" - calling the annotated method does exactly one thing, call
// sendDueDigests() - without ever going through Spring's TaskScheduler.
class JobAlertSchedulerTest {

    @Test
    void triggerDelegatesToJobAlertServiceAndNothingElse() {
        JobAlertService jobAlertService = mock(JobAlertService.class);
        JobAlertScheduler scheduler = new JobAlertScheduler(jobAlertService);

        scheduler.sendDueJobAlertDigests();

        verify(jobAlertService).sendDueDigests();
        verifyNoMoreInteractions(jobAlertService);
    }
}
