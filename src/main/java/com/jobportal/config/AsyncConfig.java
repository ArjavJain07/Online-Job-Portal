package com.jobportal.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Turns on @Async processing for NotificationService (Section 16 #1: email
// notifications), the same minimal, single-purpose shape as SchedulingConfig - one
// switch, easy to find and to remove as a unit if this feature is ever revisited.
//
// WHY A NAMED "taskExecutor" BEAN, NOT A SMALLER @Async(with no executor at all)
// Left unconfigured, @Async falls back to SimpleAsyncTaskExecutor, which spawns a brand
// new, never-reused thread for every single call - fine for the odd background task, a
// resource leak waiting to happen if a bug ever calls a notify*(...) method in a loop.
// Naming the bean "taskExecutor" is Spring's own documented convention for the executor
// @Async should use by default (AsyncExecutionAspectSupport looks for that exact name
// once more than one Executor-typed bean exists), so this bean is picked up without
// needing a qualifier on every @Async method.
//
// WHY A BEAN HERE RATHER THAN implementing AsyncConfigurer
// AsyncConfigurer.getAsyncExecutor() is a hand-written method, not a bean lookup, so nothing
// could ever override it from outside this class. Section 12.1's tests need exactly that
// override: SyncTaskExecutorConfig (src/test) replaces this bean with one that runs
// @Async methods on the calling thread, the same @Primary-beats-a-differently-named-bean
// trick FixedClockConfig already uses for Clock - see that test class for why a real
// background thread would make "was the email sent yet" a race no test may resolve by
// sleeping or polling. A plain @Bean keeps that override point open; AsyncConfigurer
// would have closed it.
//
// Small and bounded on purpose: this project only ever dispatches a handful of emails per
// request (at most one per apply/status-change/decision/reset), never a bulk broadcast, so
// a large pool would just be idle capacity. The queue is bounded too, so per hard
// requirement 3 - sending must never block or fail the caller - a genuinely stuck mail
// server can only ever delay OTHER queued emails, never the request that triggered one.
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.initialize();
        return executor;
    }
}
