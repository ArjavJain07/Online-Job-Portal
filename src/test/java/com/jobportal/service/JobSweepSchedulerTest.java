package com.jobportal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;

// Unit test for the @Scheduled trigger itself (Section 7.11), built by hand with a mocked
// JobSweepService instead of a Spring context or a running scheduler - the same shape
// FileStorageServiceTest already uses, and exactly why: no test may wait on a real timer.
// This proves the trigger really is "thin" - calling the annotated method does exactly
// one thing, call sweep() - without ever going through Spring's TaskScheduler.
class JobSweepSchedulerTest {

    @Test
    void triggerDelegatesToSweepServiceAndNothingElse() {
        JobSweepService jobSweepService = mock(JobSweepService.class);
        JobSweepScheduler scheduler = new JobSweepScheduler(jobSweepService);

        scheduler.sweepExpiredAndFilledJobs();

        verify(jobSweepService).sweep();
        verifyNoMoreInteractions(jobSweepService);
    }
}
