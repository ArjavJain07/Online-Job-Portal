package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SystemSettings;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobViewRepository;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// P-1 landing page and P-2 public job detail (Section 6.1). AC-P1-1, AC-P1-2, AC-P2-1,
// AC-P2-3, AC-P2-4.
class PublicPagesTest extends IntegrationTestBase {

    // An ordinary desktop browser User-Agent, for tests that need the dated view analytics
    // feature's BotDetector check to pass (Section 6.3 E-D5 decision 2).
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    // Well before any seed or test data (FixedClockConfig's "today" is 16 Sep 2026), so
    // findViewedAtSince(..., FAR_PAST) below returns every JobView row a test created.
    private static final LocalDateTime FAR_PAST = LocalDateTime.of(2000, 1, 1, 0, 0);

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobViewRepository jobViewRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private UserDetailsService userDetailsService;
    @PersistenceContext
    private EntityManager entityManager;

    // AC-P1-1: the landing page's "latest openings" (the 6 newest Live jobs) lists
    // exactly the 6 Live seed jobs and none of the 6 non-Live ones.
    @Test
    void homeShowsOnlyLiveJobs() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Java Developer", "Spring Boot Intern", "Frontend Developer", "QA Engineer",
                "Data Analyst", "Marketing Executive");
        assertThat(body).doesNotContain("DevOps Engineer", "Sales Intern", "Store Manager",
                "Customer Support Associate", "Python Backend Developer", "Warehouse Supervisor");
    }

    // AC-P1-2: with employerRegistrationOpen = false, the "I'm hiring" card is absent
    // (the "I'm looking for a job" card, gated by the other flag, still shows).
    @Test
    void hiringCardHiddenWhenEmployerRegistrationClosed() throws Exception {
        setEmployerRegistrationOpen(false);

        String body = mockMvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("I'm hiring");
        assertThat(body).contains("I'm looking for a job");
    }

    // AC-P2-1: an anonymous visitor gets 404 for a pending job; the owning employer sees
    // the preview banner; the admin sees the preview banner plus a "Review" button.
    @Test
    void pendingJobIs404ForPublicButVisibleToOwnerAndAdmin() throws Exception {
        Long devOpsJobId = data.jobId("DevOps Engineer");

        mockMvc.perform(get("/jobs/{id}", devOpsJobId))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));

        UserDetails owner = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/jobs/{id}", devOpsJobId).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Preview: Pending approval")));

        UserDetails admin = userDetailsService.loadUserByUsername("admin@jobportal.local");
        mockMvc.perform(get("/jobs/{id}", devOpsJobId).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Preview: Pending approval")))
                .andExpect(content().string(containsString("Review")));
    }

    // AC-P2-4: an expired but still-approved job shows the closed banner and no apply
    // panel.
    @Test
    void expiredJobShowsClosedBanner() throws Exception {
        Long pythonJobId = data.jobId("Python Backend Developer");

        String body = mockMvc.perform(get("/jobs/{id}", pythonJobId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("This job is no longer accepting applications.");
        assertThat(body).doesNotContain("Apply now");
        assertThat(body).doesNotContain("Log in to apply");
    }

    // AC-P2-3: two anonymous views in the same session count as one; a view by the
    // job's own employer never counts (decision D-19, JobSearchService.recordView).
    @Test
    void viewCountedOncePerSessionExcludingOwner() throws Exception {
        Long javaJobId = data.jobId("Java Developer");
        int before = currentViewCount(javaJobId);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/jobs/{id}", javaJobId).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/jobs/{id}", javaJobId).session(session)).andExpect(status().isOk());
        assertThat(currentViewCount(javaJobId)).isEqualTo(before + 1);

        UserDetails owner = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/jobs/{id}", javaJobId).with(user(owner))).andExpect(status().isOk());
        assertThat(currentViewCount(javaJobId)).isEqualTo(before + 1);
    }

    // Dated view analytics (JobView, not Job.viewCount - Section 6.3 E-D5): an anonymous
    // visitor with an ordinary browser User-Agent gets exactly one JobView row for a Live
    // job, following the same per-session dedupe viewCount already uses above - a second
    // view in the same session adds no second row.
    @Test
    void humanViewOfLiveJobIsRecordedAsJobView() throws Exception {
        Long javaJobId = data.jobId("Java Developer");
        Long acmeId = data.userId("hr@acme.local");
        long before = jobViewCount(acmeId, javaJobId);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/jobs/{id}", javaJobId).session(session).header("User-Agent", BROWSER_USER_AGENT))
                .andExpect(status().isOk());
        mockMvc.perform(get("/jobs/{id}", javaJobId).session(session).header("User-Agent", BROWSER_USER_AGENT))
                .andExpect(status().isOk());

        assertThat(jobViewCount(acmeId, javaJobId)).isEqualTo(before + 1);
    }

    // Decision 2 of the feature ("write amplification and bot noise"): a scanner-style
    // User-Agent still bumps viewCount exactly as before - no regression to AC-P2-3 - but
    // util.BotDetector keeps it out of the JobView table.
    @Test
    void botUserAgentBumpsViewCountButNotJobView() throws Exception {
        Long javaJobId = data.jobId("Java Developer");
        Long acmeId = data.userId("hr@acme.local");
        int viewCountBefore = currentViewCount(javaJobId);
        long jobViewsBefore = jobViewCount(acmeId, javaJobId);

        mockMvc.perform(get("/jobs/{id}", javaJobId).header("User-Agent", "curl/8.4.0"))
                .andExpect(status().isOk());

        assertThat(currentViewCount(javaJobId)).isEqualTo(viewCountBefore + 1);
        assertThat(jobViewCount(acmeId, javaJobId)).isEqualTo(jobViewsBefore);
    }

    // A request with no User-Agent header at all (every real browser sends one) is treated
    // the same as a bot by util.BotDetector, so it is not recorded as a JobView either -
    // still bumps viewCount, matching viewCountedOncePerSessionExcludingOwner above.
    @Test
    void missingUserAgentIsNotRecordedAsJobView() throws Exception {
        Long javaJobId = data.jobId("Java Developer");
        Long acmeId = data.userId("hr@acme.local");
        long before = jobViewCount(acmeId, javaJobId);

        mockMvc.perform(get("/jobs/{id}", javaJobId)).andExpect(status().isOk());

        assertThat(jobViewCount(acmeId, javaJobId)).isEqualTo(before);
    }

    // The job's own employer never creates a JobView row either, mirroring viewCount's own
    // exclusion (decision D-19): an employer checking their own listing is not a candidate
    // reading it, so it should not feed either the counter or the dated analytics.
    @Test
    void ownerViewIsNotRecordedAsJobView() throws Exception {
        Long javaJobId = data.jobId("Java Developer");
        Long acmeId = data.userId("hr@acme.local");
        long before = jobViewCount(acmeId, javaJobId);

        UserDetails owner = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/jobs/{id}", javaJobId).with(user(owner)).header("User-Agent", BROWSER_USER_AGENT))
                .andExpect(status().isOk());

        assertThat(jobViewCount(acmeId, javaJobId)).isEqualTo(before);
    }

    // The two-pane /jobs list (JobSearchTest) adds a new "job" request parameter and a
    // ".jp-jobs-detail" pane, but must not touch the standalone GET /jobs/{id} route at
    // all - it is still a public route with its own tests (this class's other methods
    // already exercise its pending/expired/view-count behaviour; this one checks the plain
    // Live case an anonymous visitor sees every day still renders with its own apply CTA).
    @Test
    void standaloneDetailPageStillWorksForLiveJob() throws Exception {
        Long javaJobId = data.jobId("Java Developer");

        String body = mockMvc.perform(get("/jobs/{id}", javaJobId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Java Developer", "Acme Technologies", "Log in to apply");
    }

    private void setEmployerRegistrationOpen(boolean open) {
        SystemSettings settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setEmployerRegistrationOpen(open);
        systemSettingsRepository.saveAndFlush(settings);
    }

    // viewCount is changed with a bulk update, which does not refresh an already-loaded
    // entity in this test's shared persistence context (Section 12.1), so the context is
    // cleared before reading it back.
    private int currentViewCount(Long jobId) {
        entityManager.clear();
        return jobRepository.findById(jobId).orElseThrow().getViewCount();
    }

    // Every JobView row recorded for one employer's job, regardless of when - tests only
    // ever add rows "now", so a fixed far-past cutoff is enough to read them all back.
    private long jobViewCount(Long employerId, Long jobId) {
        return jobViewRepository.findViewedAtSince(employerId, jobId, FAR_PAST).size();
    }
}
