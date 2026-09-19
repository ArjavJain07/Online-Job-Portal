package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.repository.JobRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Public company index and detail pages (Section 6.1 P-3, P-4 A-D6): employers with at
// least one visible job are listed publicly, and can be browsed by employer detail page.
// Disabled employers and those without visible jobs return 404 (A-D6 spec).
class CompanyBrowseTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JobRepository jobRepository;

    // /companies index lists all employers with at least one visible job.
    @Test
    void companiesIndexShowsOnlyEmployersWithVisibleJobs() throws Exception {
        String body = mockMvc.perform(get("/companies")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // "Acme Technologies" has 4 visible jobs (Java Developer, Spring Boot Intern, Frontend Developer, QA Engineer).
        // "Globex Retail" has 2 visible jobs (Data Analyst, Marketing Executive).
        // QuickHire Staffing is disabled, so it should not appear.
        assertThat(body).contains("Acme Technologies");
        assertThat(body).contains("Globex Retail");
        assertThat(body).doesNotContain("QuickHire Staffing");
        assertThat(body).contains("Browse Companies");
    }

    // /companies/{id} shows company detail: name, description, website, and their visible jobs.
    @Test
    void companyDetailShowsEmployerInfoAndVisibleJobs() throws Exception {
        Long acmeId = data.userId("hr@acme.local");

        String body = mockMvc.perform(get("/companies/{id}", acmeId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Acme Technologies");
        // Company should show their visible jobs
        assertThat(body).contains("Java Developer", "Frontend Developer", "QA Engineer", "Spring Boot Intern");
    }

    // A disabled employer returns 404, even if they have visible jobs (should never happen
    // in practice - jobs of disabled employers are not visible).
    @Test
    void disabledEmployerReturns404() throws Exception {
        Long disabledId = data.userId("jobs@quickhire.local");

        mockMvc.perform(get("/companies/{id}", disabledId))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));
    }

    // An employer with no visible jobs returns 404 (A-D6: employers must have at least one
    // visible job to be accessible). QuickHire is disabled, so has no visible jobs.
    @Test
    void employerWithNoVisibleJobsReturns404() throws Exception {
        // QuickHire Staffing is disabled, so has no visible jobs
        Long quickhireId = data.userId("jobs@quickhire.local");

        mockMvc.perform(get("/companies/{id}", quickhireId))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));
    }

    // Nonexistent employer ID returns 404.
    @Test
    void nonexistentCompanyReturns404() throws Exception {
        mockMvc.perform(get("/companies/{id}", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));
    }

    // Company detail page shows company description when present.
    @Test
    void companyDetailShowsCompanyDescription() throws Exception {
        Long acmeId = data.userId("hr@acme.local");

        String body = mockMvc.perform(get("/companies/{id}", acmeId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("About the company");
    }

    // Company detail page shows company website link when present.
    @Test
    void companyDetailShowsCompanyWebsite() throws Exception {
        Long acmeId = data.userId("hr@acme.local");

        String body = mockMvc.perform(get("/companies/{id}", acmeId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("https://acme.example");
    }

    // Company detail page shows job count in the sidebar.
    @Test
    void companyDetailShowsJobCount() throws Exception {
        Long acmeId = data.userId("hr@acme.local");

        String body = mockMvc.perform(get("/companies/{id}", acmeId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Acme has 4 visible jobs
        assertThat(body).contains("Open positions");
        assertThat(body).contains("4 jobs");
    }

    // Routes are public: accessible to anonymous visitors.
    @Test
    void companiesRoutesArePublic() throws Exception {
        mockMvc.perform(get("/companies")).andExpect(status().isOk());

        Long acmeId = data.userId("hr@acme.local");
        mockMvc.perform(get("/companies/{id}", acmeId)).andExpect(status().isOk());
    }
}
