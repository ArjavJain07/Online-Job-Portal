package com.jobportal.web.seeker;

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

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Application tracking and withdraw, S-F3 + S-D2 (Section 6.4). Priya's active
// applications are A1 Java Developer (INTERVIEW), A5 Frontend Developer (UNDER_REVIEW,
// never viewed - "Updated") and A9 Data Analyst (APPLIED); A14 Python Backend Developer
// is already REJECTED/final (Section 13.5).
class ApplicationTrackingTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    // AC-S-F3-1: Priya's list shows Frontend Developer "Under review" with the "Updated"
    // badge (A5's statusChangedAt is 2 days ago, seekerLastViewedAt is null - Section
    // 13.5); opening the detail page stamps seekerLastViewedAt and clears the badge.
    @Test
    void statusChangeShowsUpdatedBadgeUntilViewed() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long a5Id = data.applicationId("priya@demo.local", "Frontend Developer");

        assertThat(jobApplicationRepository.findById(a5Id).orElseThrow().isUpdatedForSeeker()).isTrue();

        String listBody = mockMvc.perform(get("/seeker/applications").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int frontendIndex = listBody.indexOf("Frontend Developer");
        assertThat(frontendIndex).isPositive();
        assertThat(listBody.substring(frontendIndex, frontendIndex + 400)).contains("Updated");

        String detailBody = mockMvc.perform(get("/seeker/applications/{id}", a5Id).with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("APP-00005")))
                .andExpect(content().string(containsString("Frontend Developer")))
                .andExpect(content().string(containsString("Under review")))
                .andReturn().getResponse().getContentAsString();
        // A5's timeline (Section 13.5): Applied 9 days ago (07 Sep 2026), Under review 2
        // days ago (14 Sep 2026), oldest first.
        int timelineStart = detailBody.indexOf("Timeline");
        assertThat(timelineStart).isPositive();
        String timeline = detailBody.substring(timelineStart);
        assertThat(timeline.indexOf("07 Sep 2026")).isNotNegative();
        assertThat(timeline.indexOf("07 Sep 2026")).isLessThan(timeline.indexOf("14 Sep 2026"));

        JobApplication reloaded = jobApplicationRepository.findById(a5Id).orElseThrow();
        assertThat(reloaded.getSeekerLastViewedAt()).isNotNull();
        assertThat(reloaded.isUpdatedForSeeker()).isFalse();

        String listBodyAfter = mockMvc.perform(get("/seeker/applications").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int frontendIndexAfter = listBodyAfter.indexOf("Frontend Developer");
        assertThat(listBodyAfter.substring(frontendIndexAfter, frontendIndexAfter + 400)).doesNotContain("Updated");
    }

    // Section 5.6: seekers see the softer seekerLabel, never the employer's plain one -
    // A14 (Priya, Python Backend Developer) is REJECTED, so its badge and timeline both
    // read "Not selected", never "Rejected".
    @Test
    void timelineShowsSeekerLabels() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long a14Id = data.applicationId("priya@demo.local", "Python Backend Developer");

        String body = mockMvc.perform(get("/seeker/applications/{id}", a14Id).with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not selected")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(">Rejected<");

        int timelineStart = body.indexOf("Timeline");
        assertThat(timelineStart).isPositive();
        String timeline = body.substring(timelineStart);
        assertThat(timeline).contains("Applied", "Not selected");
        // Oldest first (Section 6.4 S-F3 "Timeline"): Applied (28 days ago) precedes the
        // Rejected/"Not selected" row (21 days ago).
        assertThat(timeline.indexOf(">Applied<")).isLessThan(timeline.indexOf("Not selected"));
    }

    // AC-S-F3-2: Priya withdraws Data Analyst (A9, APPLIED - active); the flash appears,
    // the status becomes WITHDRAWN, and applying again is blocked as a duplicate. POST
    // withdraw on the already-final A14 (Python Backend Developer, REJECTED) is refused
    // and changes nothing.
    @Test
    void withdrawOnlyFromActiveStatuses() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long a9Id = data.applicationId("priya@demo.local", "Data Analyst");

        mockMvc.perform(post("/seeker/applications/{id}/withdraw", a9Id).with(user(priya)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications"))
                .andExpect(flash().attribute("success", "Your application for 'Data Analyst' has been withdrawn."));

        assertThat(jobApplicationRepository.findById(a9Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);

        mockMvc.perform(get("/seeker/jobs/{id}/apply", data.jobId("Data Analyst")).with(user(priya)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a9Id))
                .andExpect(flash().attribute("error", "You have already applied for this job."));

        Long a14Id = data.applicationId("priya@demo.local", "Python Backend Developer");
        mockMvc.perform(post("/seeker/applications/{id}/withdraw", a14Id).with(user(priya)).with(csrf())
                        .header("Referer", "http://localhost/seeker/applications/" + a14Id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a14Id))
                .andExpect(flash().attribute("error", "This application can no longer be withdrawn (status: Not selected)."));

        assertThat(jobApplicationRepository.findById(a14Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    // AC-S-D2-1: Priya's active list is exactly Java Developer, Frontend Developer and
    // Data Analyst, with chips "All 3 - Applied 1 - Under review 1 - Interview 1"; opening
    // Rohan's application (A2) is a 404 (ownership, Section 4.5 - also AC-P6-2 in
    // AccessControlTest#foreignIdsReturn404).
    @Test
    void listShowsOnlyOwnActiveApplications() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        String body = mockMvc.perform(get("/seeker/applications").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Java Developer")))
                .andExpect(content().string(containsString("Frontend Developer")))
                .andExpect(content().string(containsString("Data Analyst")))
                .andExpect(content().string(not(containsString("Python Backend Developer"))))
                .andExpect(content().string(containsString("All")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(">3<", "Applied", "Under review", "Interview");

        Long a2Id = data.applicationId("rohan@demo.local", "Java Developer");
        mockMvc.perform(get("/seeker/applications/{id}", a2Id).with(user(priya)))
                .andExpect(status().isNotFound());
    }
}
