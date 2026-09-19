package com.jobportal.web.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.repository.SavedJobRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// End-to-end tests for saved jobs (Section 16 future-work item 5): save/unsave from both
// the job list pane and the standalone job detail page, the "My saved jobs" list, and the
// "without losing the user's place" requirement itself - proven here by asserting the exact
// redirect target SafeRedirects.backOrDashboard sends the seeker back to, the same way
// ApplicationTrackingTest already exercises that helper for a withdraw button.
class SavedJobFlowTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private SavedJobRepository savedJobRepository;

    @Test
    void savingFromTheJobListPaneReturnsToTheSameListWithItsFiltersIntact() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Spring Boot Intern");
        String listUrl = "http://localhost/seeker/jobs?jobType=FULL_TIME&page=0&job=" + jobId;

        mockMvc.perform(post("/seeker/jobs/{id}/save", jobId).with(user(priya)).with(csrf())
                        .header("Referer", listUrl))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/jobs?jobType=FULL_TIME&page=0&job=" + jobId))
                .andExpect(flash().attribute("success", "Job saved. Find it any time under My saved jobs."));

        assertThat(savedJobRepository.existsByJob_IdAndSeeker_Id(jobId, data.userId("priya@demo.local"))).isTrue();

        // The pane now shows "Saved" instead of "Save" for this same job.
        mockMvc.perform(get("/seeker/jobs").param("job", jobId.toString()).with(user(priya)))
                .andExpect(content().string(containsString("Saved")));
    }

    @Test
    void savingAndUnsavingFromTheStandaloneJobDetailPageReturnsThereEachTime() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("QA Engineer");
        String detailUrl = "http://localhost/jobs/" + jobId;

        mockMvc.perform(get("/jobs/{id}", jobId).with(user(priya)))
                .andExpect(content().string(containsString("Save this job")));

        mockMvc.perform(post("/seeker/jobs/{id}/save", jobId).with(user(priya)).with(csrf())
                        .header("Referer", detailUrl))
                .andExpect(redirectedUrl("/jobs/" + jobId));

        mockMvc.perform(get("/jobs/{id}", jobId).with(user(priya)))
                .andExpect(content().string(containsString("Saved - remove")));

        mockMvc.perform(post("/seeker/jobs/{id}/unsave", jobId).with(user(priya)).with(csrf())
                        .header("Referer", detailUrl))
                .andExpect(redirectedUrl("/jobs/" + jobId))
                .andExpect(flash().attribute("success", "Job removed from your saved jobs."));

        assertThat(savedJobRepository.existsByJob_IdAndSeeker_Id(jobId, data.userId("priya@demo.local"))).isFalse();
        mockMvc.perform(get("/jobs/{id}", jobId).with(user(priya)))
                .andExpect(content().string(containsString("Save this job")));
    }

    // No Referer at all (e.g. a bookmarked POST target, or a browser that strips the
    // header) falls back to /dashboard rather than erroring - SafeRedirects' own documented
    // fallback, reused here rather than reimplemented.
    @Test
    void savingWithNoRefererFallsBackToTheDashboard() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Java Developer");

        mockMvc.perform(post("/seeker/jobs/{id}/save", jobId).with(user(priya)).with(csrf()))
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void savingIsIdempotentAndListedOnceOnTheSavedJobsPage() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Data Analyst");

        mockMvc.perform(get("/seeker/saved-jobs").with(user(priya)))
                .andExpect(content().string(containsString("You haven't saved any jobs yet")));

        mockMvc.perform(post("/seeker/jobs/{id}/save", jobId).with(user(priya)).with(csrf()));
        mockMvc.perform(post("/seeker/jobs/{id}/save", jobId).with(user(priya)).with(csrf())); // repeat click

        assertThat(savedJobRepository.findBySeeker_IdOrderBySavedAtDesc(data.userId("priya@demo.local"),
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);

        mockMvc.perform(get("/seeker/saved-jobs").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Data Analyst")))
                .andExpect(content().string(containsString("Globex Retail")));
    }

    // A pending-approval job is not visible to a seeker at all (Section 6.1 P-2) - saving it
    // must 404 exactly like opening its own detail page would, not quietly create a
    // bookmark to a job the seeker could never actually open (SavedJobService.save reuses
    // JobSearchService.findForDetail for exactly this reason).
    @Test
    void savingAJobNotVisibleToTheSeekerIs404() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long pendingJobId = data.jobId("DevOps Engineer");

        mockMvc.perform(post("/seeker/jobs/{id}/save", pendingJobId).with(user(priya)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(savedJobRepository.existsByJob_IdAndSeeker_Id(pendingJobId, data.userId("priya@demo.local"))).isFalse();
    }

    // Unsaving something never saved (already removed, or never saved to begin with) is a
    // harmless no-op, not an error - SavedJobService.unsave's own documented idempotence.
    @Test
    void unsavingSomethingNeverSavedIsAHarmlessNoOp() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Java Developer");

        mockMvc.perform(post("/seeker/jobs/{id}/unsave", jobId).with(user(priya)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job removed from your saved jobs."));
    }
}
