package com.jobportal.support;

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
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // each test rolls back, so tests don't affect each other
@Import({FixedClockConfig.class, TestData.class})
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected TestData data; // data.jobId("Data Analyst"), data.userId("priya@demo.local"), ...
}
