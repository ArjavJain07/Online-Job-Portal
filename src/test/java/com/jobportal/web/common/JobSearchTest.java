package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SystemSettings;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MvcResult;

// Public job search, GET /jobs (Section 6.1 P-2, 6.4 S-F1), plus the seeker-only view on
// GET /seeker/jobs (M5, Section 6.4 S-D1) added by #seekerViewShowsAppliedBadges. The
// other methods here cover AC-S-F1-1 and AC-S-F1-2 (public parts, M2).
class JobSearchTest extends IntegrationTestBase {

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private UserDetailsService userDetailsService;

    // AC-S-F1-1: q=java&location=pune returns exactly Java Developer and Spring Boot
    // Intern, with the "2 jobs found" header.
    @Test
    void keywordAndLocationReturnOnlyLiveMatches() throws Exception {
        String body = mockMvc.perform(get("/jobs").param("q", "java").param("location", "pune"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("2 jobs found", "Java Developer", "Spring Boot Intern");
        assertThat(body).doesNotContain("Frontend Developer", "QA Engineer", "Data Analyst", "Marketing Executive");
    }

    // AC-S-F1-2 (first clause): jobType=FULL_TIME&minSalary=600000&sort=salary returns
    // Java Developer, Frontend Developer, Data Analyst, QA Engineer, highest salary first.
    @Test
    void typeSalaryFilterAndSortWork() throws Exception {
        String body = mockMvc.perform(get("/jobs")
                        .param("jobType", "FULL_TIME")
                        .param("minSalary", "600000")
                        .param("sort", "salary"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int java = body.indexOf("Java Developer");
        int frontend = body.indexOf("Frontend Developer");
        int data = body.indexOf("Data Analyst");
        int qa = body.indexOf("QA Engineer");
        assertThat(java).isPositive();
        assertThat(java).isLessThan(frontend);
        assertThat(frontend).isLessThan(data);
        assertThat(data).isLessThan(qa);
    }

    // AC-S-F1-2 (second and third clause): an unparsable minSalary is ignored with a
    // warning and every Live job is shown; an unknown category is silently ignored, same
    // result, no warning.
    @Test
    void invalidNumbersIgnoredWithWarning() throws Exception {
        mockMvc.perform(get("/jobs").param("minSalary", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("6 jobs found")))
                .andExpect(content().string(containsString(
                        "Minimum salary must be a whole number, so that filter was ignored.")));

        mockMvc.perform(get("/jobs").param("category", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("6 jobs found")));
    }

    // Warehouse Supervisor (J12) is APPROVED with a future deadline, but QuickHire is
    // deactivated, so JobSpecifications.live() must still hide it (11.3 contract item 4).
    @Test
    void disabledEmployerJobsHidden() throws Exception {
        mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Warehouse Supervisor"))));
    }

    // Pagination links must keep every other filter (Section 7.9 PageLinks): with the
    // page size lowered to 5, the 6 Live jobs split into a 5-job page and a 1-job page,
    // and the "next page" link still carries sort=salary. The query string goes straight
    // on the URL (not through .param()), because PageLinks rebuilds the link from the
    // request's raw query string (ServletUriComponentsBuilder.fromCurrentRequest()),
    // which .param() alone does not populate.
    @Test
    void paginationKeepsFilters() throws Exception {
        setPageSize(5);

        MvcResult firstPage = mockMvc.perform(get("/jobs?sort=salary&page=0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 1-5 of 6")))
                .andReturn();
        String firstPageBody = firstPage.getResponse().getContentAsString();
        assertThat(firstPageBody).contains("page=1");
        assertThat(firstPageBody).contains("sort=salary");

        mockMvc.perform(get("/jobs?sort=salary&page=1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 6-6 of 6")));
    }

    // AC-S-D1-1 (M5): Priya's /seeker/jobs shows "Applied" on Java Developer, Frontend
    // Developer and Data Analyst (her own applications, Section 13.5) and no such badge on
    // Spring Boot Intern; q=zzz shows the empty state; with page size 5 the 6 Live jobs
    // split into a 5-card page (whose "next" link keeps sort=salary) and a 1-card page.
    @Test
    void seekerViewShowsAppliedBadges() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        String body = mockMvc.perform(get("/seeker/jobs").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertAppliedBadge(body, "Java Developer", true);
        assertAppliedBadge(body, "Frontend Developer", true);
        assertAppliedBadge(body, "Data Analyst", true);
        assertAppliedBadge(body, "Spring Boot Intern", false);

        mockMvc.perform(get("/seeker/jobs").param("q", "zzz").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No jobs match your search. Try removing some filters.")));

        setPageSize(5);
        MvcResult firstPage = mockMvc.perform(get("/seeker/jobs?sort=salary&page=0").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 1-5 of 6")))
                .andReturn();
        assertThat(firstPage.getResponse().getContentAsString()).contains("page=1", "sort=salary");

        mockMvc.perform(get("/seeker/jobs?sort=salary&page=1").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 6-6 of 6")));
    }

    // fragments/job-card only prints the "Applied" badge (class="badge app-applied") when
    // this seeker has already applied; an unapplied job's card has no such badge. Each
    // card starts with the literal "card shadow-sm mb-3" (its own, more specific class
    // string - other cards on the page use "mb-4"), so splitting on it isolates one job's
    // markup instead of risking a fixed-size window spilling into the next card.
    private void assertAppliedBadge(String body, String jobTitle, boolean expectApplied) {
        String[] cards = body.split("card shadow-sm mb-3");
        String card = null;
        for (String candidate : cards) {
            if (candidate.contains(jobTitle)) {
                card = candidate;
                break;
            }
        }
        assertThat(card).as("job card for " + jobTitle).isNotNull();
        if (expectApplied) {
            assertThat(card).contains("app-applied");
        } else {
            assertThat(card).doesNotContain("app-applied");
        }
    }

    private void setPageSize(int pageSize) {
        SystemSettings settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setPageSize(pageSize);
        systemSettingsRepository.saveAndFlush(settings);
    }
}
