package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.dto.EmployerJobHistoryRow;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.service.JobService;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

// Employer job posting history (Section 6.3 E-D4). Reuses JobService#employerJobHistory
// directly wherever a numeric field (applications, hired) matters, rather than scraping
// HTML, and MockMvc wherever the page itself (headings, filter tabs, the empty state) is
// what is under test.
class EmployerJobHistoryTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private JobService jobService;
    @Autowired
    private Clock clock;

    // AC-E-D4-1: closing Java Developer removes it from /employer/jobs and adds it to
    // /employer/jobs/history with today's closed date and its 4 applications (A1-A4,
    // Section 13.5).
    @Test
    void closedJobMovesToHistory() throws Exception {
        UserDetails employer = acme();
        Long employerId = data.userId("hr@acme.local");
        Long id = data.jobId("Java Developer");

        mockMvc.perform(post("/employer/jobs/{id}/close", id).with(user(employer)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success",
                        "Job 'Java Developer' closed. It is no longer accepting applications."));

        mockMvc.perform(get("/employer/jobs").with(user(employer)))
                .andExpect(content().string(not(containsString("Java Developer"))));

        JobService.EmployerJobHistory history = jobService.employerJobHistory(employerId, "CLOSED", null);
        EmployerJobHistoryRow row = history.rows().stream()
                .filter(r -> r.job().getTitle().equals("Java Developer")).findFirst().orElseThrow();
        assertThat(row.closedOnText()).isEqualTo("16 Sep 2026"); // today under the fixed test clock
        assertThat(row.applications()).isEqualTo(4);

        mockMvc.perform(get("/employer/jobs/history").with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Java Developer")))
                .andExpect(content().string(containsString("16 Sep 2026")));
    }

    // AC-E-D4-2: after the admin rejects DevOps Engineer with a reason and Acme edits and
    // resubmits it, its timeline shows Posted, then Rejected (with the reason), then
    // Resubmitted, in that order, and the job ends up PENDING_APPROVAL.
    @Test
    void timelineShowsDecisionsInOrder() throws Exception {
        UserDetails admin = userDetailsService.loadUserByUsername("admin@jobportal.local");
        UserDetails employer = acme();
        Long id = data.jobId("DevOps Engineer");
        String reason = "Please add the on-call expectations to the description.";

        mockMvc.perform(post("/admin/jobs/{id}/reject", id).with(user(admin)).with(csrf()).param("reason", reason))
                .andExpect(status().is3xxRedirection());
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.REJECTED);

        Job devOps = jobRepository.findById(id).orElseThrow();
        mockMvc.perform(post("/employer/jobs/{id}", id).with(user(employer)).with(csrf()).params(formParamsFor(devOps)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'DevOps Engineer' resubmitted for approval."));
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);

        String body = mockMvc.perform(get("/employer/jobs/{id}", id).with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int postedIndex = body.indexOf("Posted");
        int rejectedIndex = body.indexOf("Rejected", postedIndex);
        int resubmittedIndex = body.indexOf("Resubmitted", rejectedIndex);
        assertThat(postedIndex).isPositive();
        assertThat(rejectedIndex).isGreaterThan(postedIndex);
        assertThat(resubmittedIndex).isGreaterThan(rejectedIndex);
        assertThat(body).contains(reason);
    }

    // AC-E-D4-3: a job closed from APPROVED reopens straight back to Live with a valid
    // new deadline; a past new deadline is refused; with jobApprovalRequired off, closing
    // and reopening the REJECTED Store Manager still goes to PENDING_APPROVAL, not Live
    // (5.5: the setting never lets reopen skip a rejected job's re-review). Globex's
    // history (checked first, before Store Manager's own status changes below) shows
    // Customer Support Associate Closed with 2 applications (A12 Hired, A13 Rejected) and
    // 1 hired, and Store Manager Rejected with its admin reason.
    @Test
    void reopenRestoresLiveOnlyIfClosedFromApproved() throws Exception {
        UserDetails acme = acme();
        UserDetails globex = userDetailsService.loadUserByUsername("talent@globex.local");
        Long globexId = data.userId("talent@globex.local");

        JobService.EmployerJobHistory before = jobService.employerJobHistory(globexId, null, null);
        EmployerJobHistoryRow supportRow = before.rows().stream()
                .filter(r -> r.job().getTitle().equals("Customer Support Associate")).findFirst().orElseThrow();
        assertThat(supportRow.job().getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(supportRow.applications()).isEqualTo(2);
        assertThat(supportRow.hired()).isEqualTo(1);
        EmployerJobHistoryRow storeRow = before.rows().stream()
                .filter(r -> r.job().getTitle().equals("Store Manager")).findFirst().orElseThrow();
        assertThat(storeRow.job().getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(storeRow.decisionText()).startsWith("Rejected")
                .contains("Description too vague: please add the store location and shift timings.");

        // Java Developer: closed from APPROVED, so reopening with a valid new deadline
        // goes straight back to Live.
        Long javaDeveloperId = data.jobId("Java Developer");
        mockMvc.perform(post("/employer/jobs/{id}/close", javaDeveloperId).with(user(acme)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/employer/jobs/{id}/reopen", javaDeveloperId).with(user(acme)).with(csrf())
                        .param("newDeadline", LocalDate.now(clock).plusDays(14).toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/jobs/" + javaDeveloperId))
                .andExpect(flash().attribute("success", "Job 'Java Developer' reopened and is live again."));
        assertThat(jobRepository.findById(javaDeveloperId).orElseThrow().getStatus()).isEqualTo(JobStatus.APPROVED);
        mockMvc.perform(get("/jobs")).andExpect(content().string(containsString("Java Developer")));

        // QA Engineer (0 applications): a past new deadline is refused and the job stays
        // Closed.
        Long qaEngineerId = data.jobId("QA Engineer");
        mockMvc.perform(post("/employer/jobs/{id}/close", qaEngineerId).with(user(acme)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/employer/jobs/{id}/reopen", qaEngineerId).with(user(acme)).with(csrf())
                        .param("newDeadline", LocalDate.now(clock).minusDays(1).toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", JobService.REOPEN_DEADLINE_RANGE_MESSAGE));
        assertThat(jobRepository.findById(qaEngineerId).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);

        // Globex, approval off: closing then reopening the REJECTED Store Manager still
        // goes to PENDING_APPROVAL.
        var settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setJobApprovalRequired(false);
        systemSettingsRepository.saveAndFlush(settings);

        Long storeManagerId = storeRow.job().getId();
        mockMvc.perform(post("/employer/jobs/{id}/close", storeManagerId).with(user(globex)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/employer/jobs/{id}/reopen", storeManagerId).with(user(globex)).with(csrf())
                        .param("newDeadline", LocalDate.now(clock).plusDays(20).toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'Store Manager' reopened and sent for approval."));
        assertThat(jobRepository.findById(storeManagerId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);
        mockMvc.perform(get("/jobs")).andExpect(content().string(not(containsString("Store Manager"))));
    }

    // Section 6.3 E-D4 screen note: the summary line always covers every status,
    // regardless of the active filter tab.
    @Test
    void historyCountsMatchSeedData() throws Exception {
        mockMvc.perform(get("/employer/jobs/history").with(user(acme())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "6 jobs posted: 4 live, 1 expired, 1 pending, 0 rejected, 0 closed")));

        UserDetails globex = userDetailsService.loadUserByUsername("talent@globex.local");
        mockMvc.perform(get("/employer/jobs/history").with(user(globex)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "5 jobs posted: 2 live, 0 expired, 1 pending, 1 rejected, 1 closed")));
    }

    // AC-E-D4-4: the Expired and Live filters are built in SQL (7.9) so they only ever
    // show the jobs with that computed display status; the Closed filter is empty for
    // Acme and shows the "Post a job" empty state.
    @Test
    void liveAndExpiredFiltersAndEmptyState() throws Exception {
        UserDetails employer = acme();

        String expired = mockMvc.perform(get("/employer/jobs/history").param("status", "EXPIRED").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(expired).contains("Python Backend Developer");
        assertThat(expired).doesNotContain("Java Developer", "Spring Boot Intern", "Frontend Developer",
                "QA Engineer", "DevOps Engineer");

        String live = mockMvc.perform(get("/employer/jobs/history").param("status", "LIVE").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(live).contains("Java Developer", "Spring Boot Intern", "Frontend Developer", "QA Engineer");
        assertThat(live).doesNotContain("Python Backend Developer", "DevOps Engineer");

        mockMvc.perform(get("/employer/jobs/history").param("status", "CLOSED").with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No jobs match this filter. Post a job to get started.")))
                .andExpect(content().string(containsString("Post a job")));
    }

    private UserDetails acme() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }

    private MultiValueMap<String, String> formParamsFor(Job job) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("title", job.getTitle());
        params.set("description", job.getDescription());
        params.set("requirements", job.getRequirements());
        params.set("skills", String.join(", ", job.skillList()));
        params.set("category", job.getCategory().name());
        params.set("jobType", job.getJobType().name());
        params.set("workMode", job.getWorkMode().name());
        params.set("location", job.getLocation());
        params.set("salaryMin", String.valueOf(job.getSalaryMin()));
        params.set("salaryMax", String.valueOf(job.getSalaryMax()));
        params.set("minExperienceYears", String.valueOf(job.getMinExperienceYears()));
        params.set("openings", String.valueOf(job.getOpenings()));
        params.set("applicationDeadline", job.getApplicationDeadline().toString());
        return params;
    }
}
