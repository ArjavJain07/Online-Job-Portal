package com.jobportal.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Base class for every web/integration test (Section 12.1). One Spring context is cached
// across all subclasses that share these exact annotations, so the suite stays fast even
// though DataSeeder loads the whole Section 13 dataset once at context start.
//
// RecordingMailService and SyncTaskExecutorConfig (Section 16 #1) are imported here, not
// only where a test needs them, so every subclass shares the one cached context - adding
// them to a handful of test classes instead would fork a second context (Section 12.1's
// whole point is to avoid exactly that cost). See each class's own comment for why it
// exists; mailSent is cleared before every test because both beans are context-scoped
// singletons that would otherwise leak state from one test method into the next.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // each test rolls back, so tests don't affect each other
@Import({FixedClockConfig.class, TestData.class, RecordingMailService.class, SyncTaskExecutorConfig.class})
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected TestData data; // data.jobId("Data Analyst"), data.userId("priya@demo.local"), ...

    @Autowired
    protected RecordingMailService mailSent;

    @BeforeEach
    void clearRecordedMail() {
        mailSent.clear();
    }
}
