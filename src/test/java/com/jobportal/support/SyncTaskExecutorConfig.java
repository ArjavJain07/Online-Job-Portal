package com.jobportal.support;

import java.util.concurrent.Executor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

// Replaces AsyncConfig's thread pool with an executor that runs every @Async method
// (NotificationService, Section 16 #1) on the CALLING thread, for every integration test -
// the same @Primary-beats-a-differently-named-bean trick FixedClockConfig already uses for
// Clock, imported alongside it in IntegrationTestBase.
//
// Why this exists: a genuinely separate background thread would make "has the
// notification fired yet" a race that a test would have to poll or sleep for, and Section
// 12.1 allows neither. Running synchronously instead turns every notify*(...) call back
// into an ordinary method call FOR THE DURATION OF A TEST ONLY, so a test can call, say,
// PasswordResetService.requestReset(...) and immediately assert on
// RecordingMailService.sent() with no waiting of any kind. Production keeps the real pool
// (AsyncConfig): hard requirement 3 ("never block the caller") is about a live web
// request, not about how a test happens to observe the result afterwards, and a test
// thread blocking on its own synchronous call is not the caller being blocked by this
// feature - it is simply how the test was written.
@TestConfiguration
public class SyncTaskExecutorConfig {

    @Bean
    @Primary
    Executor syncTaskExecutor() {
        return new SyncTaskExecutor();
    }
}
