package com.jobportal.web.admin;

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
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.service.JobModerationService;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.web.form.JobReviewForm;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Admin job listing management (Section 6.2 A-F2/A-D2). J5 DevOps Engineer (Acme, submitted
// 1 day ago) and J8 Sales Intern (Globex, submitted 2 days ago) are the two seeded
// PENDING_APPROVAL jobs (Section 13.4); their ids equal their seed codes on a fresh
// database, the same rule PageRenderSmokeTest already relies on for /jobs/1 (Section 13.1),
// but this class still looks them up by title through TestData so it reads clearly.
class AdminJobApprovalTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private Clock clock;

    // AC-A-F2-1: approving makes the job live, sets approvedAt, and the review page's
    // timeline shows who approved it.
    @Test
    void approveMakesJobLive() throws Exception {
        UserDetails admin = admin();
        Long devOpsId = data.jobId("DevOps Engineer");

        mockMvc.perform(post("/admin/jobs/{id}/approve", devOpsId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/jobs"))
                .andExpect(flash().attribute("success", "Job 'DevOps Engineer' approved and is now live."));

        Job job = jobRepository.findById(devOpsId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(job.getApprovedAt()).isNotNull();

        mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DevOps Engineer")));

        mockMvc.perform(get("/admin/jobs/{id}", devOpsId).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("by Site Admin")));
    }

    // AC-A-F2-2: an empty reason re-renders the review-style form with the field error and
    // leaves the job pending; a real reason rejects it and the reason is stored where the
    // employer will read it once /employer/jobs exists (M4).
    @Test
    void rejectRequiresReasonAndShowsToEmployer() throws Exception {
        UserDetails admin = admin();
        Long salesInternId = data.jobId("Sales Intern");

        mockMvc.perform(post("/admin/jobs/{id}/reject", salesInternId).with(user(admin)).with(csrf())
                        .param("reason", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(JobReviewForm.REASON_MESSAGE)));
        assertThat(jobRepository.findById(salesInternId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);

        String reason = "Please add the stipend payment schedule.";
        mockMvc.perform(post("/admin/jobs/{id}/reject", salesInternId).with(user(admin)).with(csrf())
                        .param("reason", reason))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/jobs"))
                .andExpect(flash().attribute("success", "Job 'Sales Intern' rejected. The employer can see your reason."));

        Job rejected = jobRepository.findById(salesInternId).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo(reason);
    }

    // AC-A-F2-3 (first two clauses): taking down a live job removes it from /jobs but
    // leaves its applications (Marketing Executive/J7 has A10 and A11, Section 13.5) alone.
    @Test
    void takeDownHidesJobKeepsApplications() throws Exception {
        UserDetails admin = admin();
        Long marketingId = data.jobId("Marketing Executive");
        long applicationsBefore = jobApplicationRepository.countByJob_Id(marketingId);
        assertThat(applicationsBefore).isEqualTo(2);

        mockMvc.perform(get("/jobs")).andExpect(content().string(containsString("Marketing Executive")));

        mockMvc.perform(post("/admin/jobs/{id}/take-down", marketingId).with(user(admin)).with(csrf())
                        .param("reason", "Position filled outside the portal."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/jobs/" + marketingId))
                .andExpect(flash().attribute("success", "Job 'Marketing Executive' taken down. The employer can see your reason."));

        mockMvc.perform(get("/jobs")).andExpect(content().string(not(containsString("Marketing Executive"))));
        assertThat(jobApplicationRepository.countByJob_Id(marketingId)).isEqualTo(applicationsBefore);
    }

    // AC-A-F2-3 (last clause): a pending job whose deadline has already passed can't be
    // approved.
    @Test
    void approveBlockedWhenDeadlinePassed() throws Exception {
        UserDetails admin = admin();
        Long salesInternId = data.jobId("Sales Intern");
        Job job = jobRepository.findById(salesInternId).orElseThrow();
        job.setApplicationDeadline(LocalDate.now(clock).minusDays(1));
        jobRepository.saveAndFlush(job);

        mockMvc.perform(post("/admin/jobs/{id}/approve", salesInternId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", JobModerationService.DEADLINE_PASSED_MESSAGE));

        assertThat(jobRepository.findById(salesInternId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);
    }

    // AC-A-D2-1: the default (pending) tab lists exactly Sales Intern then DevOps Engineer,
    // oldest submitted first, each with an Approve button and an inline reject form;
    // ?status=ALL covers all 12 seeded jobs with the default page size; an unrecognised
    // status falls back to the pending tab; an employer is refused the page entirely.
    @Test
    void pendingTabOldestFirst() throws Exception {
        UserDetails admin = admin();

        String body = mockMvc.perform(get("/admin/jobs").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int salesIndex = body.indexOf("Sales Intern");
        int devOpsIndex = body.indexOf("DevOps Engineer");
        assertThat(salesIndex).isPositive();
        assertThat(devOpsIndex).isPositive();
        assertThat(salesIndex).isLessThan(devOpsIndex);
        assertThat(body).contains("Approve", "Reason for rejecting");

        String allBody = mockMvc.perform(get("/admin/jobs").param("status", "ALL").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(allBody).contains("Showing 1-10 of 12");

        String nopeBody = mockMvc.perform(get("/admin/jobs").param("status", "NOPE").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nopeBody).contains("Sales Intern", "DevOps Engineer");

        UserDetails employer = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/admin/jobs").with(user(employer))).andExpect(status().isForbidden());
    }

    private UserDetails admin() {
        return userDetailsService.loadUserByUsername("admin@jobportal.local");
    }
}
