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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.JobStatus;
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

// Employer job posting and management (Section 6.3 E-F1/E-D1). Acme (hr@acme.local) owns
// J1-J5 and J11 (Section 13.4: Java Developer, Spring Boot Intern, Frontend Developer, QA
// Engineer, DevOps Engineer, Python Backend Developer) - exactly the 6 active jobs
// AC-E-F1-3 counts, and Java Developer's 4 applications (A1-A4, Section 13.5) are what
// #deleteOnlyWithoutApplications refuses to delete. Seed ids equal seed codes on a fresh
// database (13.1), but every test below still looks jobs up by title through TestData.
class EmployerJobTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private Clock clock;

    // AC-E-F1-1: a valid post starts PENDING_APPROVAL (approval required is on by
    // default, 7.5), shows the flash, is listed on /employer/jobs with the Pending
    // approval badge, is not on the public /jobs list, and its detail timeline shows
    // "Posted" (5.5's "(new)" transition row). AC-A-F3-2 (first clause, deferred from
    // SystemSettingsTest until JobService.create existed): with jobApprovalRequired off,
    // a second job goes live immediately instead.
    @Test
    void postJobPendingWithConfirmation() throws Exception {
        UserDetails employer = employer();

        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(validJobParams()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success",
                        "Job 'Cloud Support Engineer' submitted for approval. You'll see the decision under My Jobs."));

        Job job = jobRepository.findAll().stream()
                .filter(j -> j.getTitle().equals("Cloud Support Engineer")).findFirst().orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);

        mockMvc.perform(get("/employer/jobs").with(user(employer)))
                .andExpect(content().string(containsString("Cloud Support Engineer")))
                .andExpect(content().string(containsString("Pending approval")));

        mockMvc.perform(get("/jobs"))
                .andExpect(content().string(not(containsString("Cloud Support Engineer"))));

        mockMvc.perform(get("/employer/jobs/{id}", job.getId()).with(user(employer)))
                .andExpect(content().string(containsString("Posted")));

        var settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setJobApprovalRequired(false);
        systemSettingsRepository.saveAndFlush(settings);

        MultiValueMap<String, String> secondJob = validJobParams();
        secondJob.set("title", "Remote Support Specialist");
        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(secondJob))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'Remote Support Specialist' is now live."));

        Job liveJob = jobRepository.findAll().stream()
                .filter(j -> j.getTitle().equals("Remote Support Specialist")).findFirst().orElseThrow();
        assertThat(liveJob.getStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(liveJob.getApprovedAt()).isNotNull();
        mockMvc.perform(get("/jobs")).andExpect(content().string(containsString("Remote Support Specialist")));
    }

    // AC-E-F1-2: each invalid case re-renders the form with its own field error and
    // saves nothing.
    @Test
    void invalidSalaryDeadlineOrDescriptionRejected() throws Exception {
        UserDetails employer = employer();
        long before = jobRepository.count();

        MultiValueMap<String, String> badSalary = validJobParams();
        badSalary.set("salaryMin", "900000");
        badSalary.set("salaryMax", "600000");
        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(badSalary))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Maximum salary must be at least the minimum salary.")));

        MultiValueMap<String, String> badDeadline = validJobParams();
        badDeadline.set("applicationDeadline", LocalDate.now(clock).minusDays(1).toString());
        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(badDeadline))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(JobService.DEADLINE_RANGE_MESSAGE)));

        MultiValueMap<String, String> badDescription = validJobParams();
        badDescription.set("description", "Too short.");
        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(badDescription))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Description must be 30-4000 characters.")));

        assertThat(jobRepository.count()).isEqualTo(before);
    }

    // AC-E-F1-3: with the limit set to 6, Acme's existing 6 active jobs (Java Developer,
    // Spring Boot Intern, Frontend Developer, QA Engineer, DevOps Engineer, Python
    // Backend Developer - Section 13.4) trip the limit on the very next post.
    @Test
    void activeJobLimitEnforced() throws Exception {
        UserDetails employer = employer();
        var settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setMaxActiveJobsPerEmployer(6);
        systemSettingsRepository.saveAndFlush(settings);

        mockMvc.perform(post("/employer/jobs").with(user(employer)).with(csrf()).params(validJobParams()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "You already have 6 active jobs (the limit set by the administrator). "
                                + "Close a job before posting another.")));

        assertThat(jobRepository.findAll().stream().anyMatch(j -> j.getTitle().equals("Cloud Support Engineer")))
                .isFalse();
    }

    // AC-E-D1-1: Acme's /employer/jobs lists its 6 jobs (Python Backend Developer shown
    // Expired, its deadline having passed - Section 13.4) and none of Globex's; a
    // foreign job's edit form is a 404 (Section 4.5).
    @Test
    void onlyOwnJobsAccessible() throws Exception {
        UserDetails employer = employer();

        String body = mockMvc.perform(get("/employer/jobs").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Java Developer", "Spring Boot Intern", "Frontend Developer", "QA Engineer",
                "DevOps Engineer", "Python Backend Developer", "Expired");
        assertThat(body).doesNotContain("Data Analyst", "Marketing Executive", "Store Manager");

        Long dataAnalystId = data.jobId("Data Analyst");
        mockMvc.perform(get("/employer/jobs/{id}/edit", dataAnalystId).with(user(employer)))
                .andExpect(status().isNotFound());
    }

    // AC-E-D1-2: a content change (title) on the Live Java Developer sends it back to
    // PENDING_APPROVAL and off /jobs; changing only the deadline on the (also Live)
    // Frontend Developer keeps it Live (5.5's "content fields" list excludes the
    // deadline).
    @Test
    void contentEditTriggersReapprovalDeadlineEditDoesNot() throws Exception {
        UserDetails employer = employer();
        Job javaDeveloper = data.job("Java Developer");
        Long id = javaDeveloper.getId();

        MultiValueMap<String, String> titleChange = formParamsFor(javaDeveloper);
        titleChange.set("title", "Senior Java Developer");
        mockMvc.perform(post("/employer/jobs/{id}", id).with(user(employer)).with(csrf()).params(titleChange))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'Senior Java Developer' updated and sent for "
                        + "re-approval. It is hidden from job seekers until approved."));
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING_APPROVAL);
        mockMvc.perform(get("/jobs")).andExpect(content().string(not(containsString("Senior Java Developer"))));

        Job frontendDeveloper = data.job("Frontend Developer");
        Long frontendId = frontendDeveloper.getId();
        MultiValueMap<String, String> deadlineChange = formParamsFor(frontendDeveloper);
        deadlineChange.set("applicationDeadline", LocalDate.now(clock).plusDays(45).toString());
        mockMvc.perform(post("/employer/jobs/{id}", frontendId).with(user(employer)).with(csrf()).params(deadlineChange))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'Frontend Developer' updated."));
        assertThat(jobRepository.findById(frontendId).orElseThrow().getStatus()).isEqualTo(JobStatus.APPROVED);
        mockMvc.perform(get("/jobs")).andExpect(content().string(containsString("Frontend Developer")));
    }

    // AC-E-D1-3: QA Engineer (0 applications, Section 13.4) deletes cleanly; Java
    // Developer (4 applications: A1-A4, Section 13.5) refuses the same request with the
    // close-instead message - only reachable directly, since both templates disable the
    // button (Section 6.3 E-D1 note); closing Java Developer instead succeeds and moves
    // it to history (E-D4).
    @Test
    void deleteOnlyWithoutApplications() throws Exception {
        UserDetails employer = employer();
        Long qaEngineerId = data.jobId("QA Engineer");

        mockMvc.perform(post("/employer/jobs/{id}/delete", qaEngineerId).with(user(employer)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Job 'QA Engineer' deleted."));
        assertThat(jobRepository.findById(qaEngineerId)).isEmpty();

        Long javaDeveloperId = data.jobId("Java Developer");
        mockMvc.perform(post("/employer/jobs/{id}/delete", javaDeveloperId).with(user(employer)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        "This job has 4 applications, so it can't be deleted. Close it instead."));
        assertThat(jobRepository.findById(javaDeveloperId)).isPresent();

        mockMvc.perform(post("/employer/jobs/{id}/close", javaDeveloperId).with(user(employer)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jobRepository.findById(javaDeveloperId).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    // 5.5: "A CLOSED job cannot be edited." Closing QA Engineer (0 applications, so this
    // test does not depend on the ordering of any other test) and then trying both the
    // GET edit form and a direct POST update are both refused with the same message.
    @Test
    void closedJobCannotBeEdited() throws Exception {
        UserDetails employer = employer();
        Job qaEngineer = data.job("QA Engineer");
        Long id = qaEngineer.getId();
        mockMvc.perform(post("/employer/jobs/{id}/close", id).with(user(employer)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/employer/jobs/{id}/edit", id).with(user(employer)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", JobService.CLOSED_CANNOT_EDIT_MESSAGE));

        // The re-rendered page HTML-escapes the message's apostrophe (th:text, Section
        // 11.3 contract item 9), so the raw response is checked in two apostrophe-free
        // halves rather than against the message constant verbatim.
        mockMvc.perform(post("/employer/jobs/{id}", id).with(user(employer)).with(csrf()).params(formParamsFor(qaEngineer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Closed jobs can")))
                .andExpect(content().string(containsString("t be edited. Reopen the job first.")));
    }

    private UserDetails employer() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }

    private MultiValueMap<String, String> validJobParams() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("title", "Cloud Support Engineer");
        params.set("description", "We help customers troubleshoot AWS infrastructure issues and keep "
                + "production systems healthy around the clock.");
        params.set("requirements", "2+ years of AWS support experience and strong troubleshooting skills.");
        params.set("skills", "AWS, Linux, Networking");
        params.set("category", "SOFTWARE_DEVELOPMENT");
        params.set("jobType", "FULL_TIME");
        params.set("workMode", "REMOTE");
        params.set("location", "Pune");
        params.set("salaryMin", "500000");
        params.set("salaryMax", "800000");
        params.set("minExperienceYears", "2");
        params.set("openings", "1");
        params.set("applicationDeadline", LocalDate.now(clock).plusDays(30).toString());
        return params;
    }

    // The same values EmployerJobController#toForm would pre-fill an edit form with, so
    // a test can change just the one field it cares about.
    private MultiValueMap<String, String> formParamsFor(Job job) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("title", job.getTitle());
        params.set("description", job.getDescription());
        params.set("requirements", job.getRequirements());
        params.set("skills", job.getSkills());
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
