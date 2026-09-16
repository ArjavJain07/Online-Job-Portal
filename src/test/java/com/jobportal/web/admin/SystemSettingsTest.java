package com.jobportal.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.exception.FileValidationException;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.service.FileStorageService;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.support.TestFiles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

// System settings (Section 6.2 A-F3/A-D3, field table in 7.5). SettingsService.get() reads
// the database on every call (no cache), so a change saved through the form takes effect on
// the very next request/service call inside the same test - that is the point of A-F3, and
// what several assertions below check directly.
//
// Every POST builds its params from defaultSettingsParams() and overrides only the field(s)
// it cares about with MultiValueMap.set(...), which REPLACES that key's value. Calling
// MockHttpServletRequestBuilder.param(name, ...) a second time for a name already set would
// instead ADD a second value for it, and Spring's data binder does not simply take the
// last one: a String property (siteName) gets every value joined with a comma, while a
// simple numeric property (maxResumeSizeMb) silently keeps only the first one - either way
// not the single override value the test intended.
class SystemSettingsTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private FileStorageService fileStorageService;

    // AC-A-F3-1: turning seeker registration off closes /register/seeker and hides its
    // card on /register, immediately (the very next request).
    @Test
    void disablingSeekerRegistrationBlocksIt() throws Exception {
        UserDetails admin = admin();
        MultiValueMap<String, String> params = defaultSettingsParams();
        params.set("employerRegistrationOpen", "true");
        // seekerRegistrationOpen left out: an unchecked checkbox submits nothing, so false.

        mockMvc.perform(post("/admin/settings").params(params).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Settings saved. Changes apply immediately."));

        mockMvc.perform(get("/register/seeker"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Job seeker registration is currently closed. Please check back later.")));

        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Register as a job seeker"))));
    }

    // AC-A-F3-2 (second clause only - the first clause, "a new job goes live
    // immediately", needs JobService.create, which is an M4 deliverable and does not exist
    // yet; EmployerJobTest#postJobPendingWithConfirmation covers it once M4 ships).
    // Switching approval off must not retroactively approve jobs that are already pending -
    // DevOps Engineer and Sales Intern (the two seeded PENDING_APPROVAL jobs, Section 13.4)
    // stay pending.
    @Test
    void approvalOffAutoApprovesNewJobsOnly() throws Exception {
        UserDetails admin = admin();
        Long devOpsId = data.jobId("DevOps Engineer");
        Long salesInternId = data.jobId("Sales Intern");
        MultiValueMap<String, String> params = defaultSettingsParams();
        params.set("seekerRegistrationOpen", "true");
        params.set("employerRegistrationOpen", "true");
        // jobApprovalRequired left out: turned off.

        mockMvc.perform(post("/admin/settings").params(params).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(settings().isJobApprovalRequired()).isFalse();
        assertThat(jobRepository.findById(devOpsId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);
        assertThat(jobRepository.findById(salesInternId).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);
    }

    // AC-A-F3-3 (first clause): FileStorageService.store enforces maxResumeSizeMb, read
    // fresh from the settings row on every call (7.5) - proven here by changing the limit
    // and calling store() in the same test, without any caching getting in the way.
    @Test
    void resumeSizeLimitApplied() throws Exception {
        UserDetails admin = admin();
        MultiValueMap<String, String> params = defaultSettingsParams();
        params.set("maxResumeSizeMb", "1");
        params.set("seekerRegistrationOpen", "true");
        params.set("employerRegistrationOpen", "true");
        params.set("jobApprovalRequired", "true");

        mockMvc.perform(post("/admin/settings").params(params).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(settings().getMaxResumeSizeMb()).isEqualTo(1);

        byte[] oversized = TestFiles.pdfBytesOfSize(2 * 1024 * 1024); // 2 MB > the new 1 MB limit
        MockMultipartFile file = new MockMultipartFile("resumeFile", "cv.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("File is larger than 1 MB.");
    }

    // AC-A-F3-3 (second clause): the new site name shows on the navbar brand and in the
    // <title> of the very next page rendered (GlobalModelAttributes, 7.5).
    @Test
    void siteNameShownInNavbar() throws Exception {
        UserDetails admin = admin();
        MultiValueMap<String, String> params = defaultSettingsParams();
        params.set("siteName", "CampusJobs");
        params.set("seekerRegistrationOpen", "true");
        params.set("employerRegistrationOpen", "true");
        params.set("jobApprovalRequired", "true");

        mockMvc.perform(post("/admin/settings").params(params).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String page = mockMvc.perform(get("/admin/dashboard").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains(">CampusJobs<");
        assertThat(page).contains("| CampusJobs</title>");
    }

    // AC-A-D3-1: an invalid page size and a blank site name are both flagged and nothing is
    // saved; unticking every resume type shows its own message.
    @Test
    void invalidValuesSaveNothing() throws Exception {
        UserDetails admin = admin();
        SystemSettings before = systemSettingsRepository.findById(1L).orElseThrow();
        String originalSiteName = before.getSiteName();
        int originalPageSize = before.getPageSize();

        MultiValueMap<String, String> badRange = defaultSettingsParams();
        badRange.set("siteName", "");
        badRange.set("pageSize", "500");

        mockMvc.perform(post("/admin/settings").params(badRange).with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Settings were not saved. Please fix the highlighted fields.")))
                .andExpect(content().string(containsString("Items per page must be between 5 and 50.")));

        SystemSettings after = systemSettingsRepository.findById(1L).orElseThrow();
        assertThat(after.getSiteName()).isEqualTo(originalSiteName);
        assertThat(after.getPageSize()).isEqualTo(originalPageSize);

        MultiValueMap<String, String> noResumeTypes = defaultSettingsParams();
        noResumeTypes.remove("allowedResumeTypes"); // no type ticked

        mockMvc.perform(post("/admin/settings").params(noResumeTypes).with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose at least one resume type.")));
    }

    private SystemSettings settings() {
        return systemSettingsRepository.findById(1L).orElseThrow();
    }

    // A valid, complete set of POST /admin/settings params at the current defaults, so each
    // test only overrides the one or two fields it cares about (see the class comment).
    private MultiValueMap<String, String> defaultSettingsParams() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("siteName", "JobPortal");
        params.set("pageSize", "10");
        params.set("maxActiveJobsPerEmployer", "20");
        params.set("maxResumeSizeMb", "2");
        params.addAll("allowedResumeTypes", List.of("pdf", "doc", "docx"));
        params.set("feedRefreshSeconds", "5");
        return params;
    }

    private UserDetails admin() {
        return userDetailsService.loadUserByUsername("admin@jobportal.local");
    }
}
