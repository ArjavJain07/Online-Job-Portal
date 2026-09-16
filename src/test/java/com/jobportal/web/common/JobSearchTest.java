package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SystemSettings;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

// Public job search, GET /jobs (Section 6.1 P-2, 6.4 S-F1). The seeker-only case
// (#seekerViewShowsAppliedBadges, on /seeker/jobs) is built in M5; the public parts here
// cover AC-S-F1-1 and AC-S-F1-2.
class JobSearchTest extends IntegrationTestBase {

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

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

    private void setPageSize(int pageSize) {
        SystemSettings settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setPageSize(pageSize);
        systemSettingsRepository.saveAndFlush(settings);
    }
}
