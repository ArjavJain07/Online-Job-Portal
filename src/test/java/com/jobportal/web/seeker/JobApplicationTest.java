package com.jobportal.web.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.User;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.support.TestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Job application, S-F2 (Section 6.4). Priya's own applications (A1 Java Developer, A5
// Frontend Developer, A9 Data Analyst, A14 Python Backend Developer - Section 13.5) are
// used throughout for the duplicate-check cases; Spring Boot Intern (J2) is Live and Priya
// has never applied to it, so it is used for the "fresh application" cases.
class JobApplicationTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private SeekerProfileRepository seekerProfileRepository;
    @Autowired
    private UserRepository userRepository;

    // AC-S-F2-1: Priya applies to Spring Boot Intern with her profile resume and a cover
    // letter; she lands on the confirmation with a reference matching APP-\d{5} (the 16th
    // application seeded - APP-00016, the same number Section 6.4's own output example
    // uses); the application is APPLIED, appears in Acme's employer list, and its
    // resumeStoredName differs from her profile's (business rule 5: an independent copy).
    @Test
    void validApplicationShowsConfirmationReference() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Spring Boot Intern");
        String profileResumeStoredName = seekerProfileRepository.findByUser_Id(data.userId("priya@demo.local"))
                .orElseThrow().getResumeStoredName();

        mockMvc.perform(multipart("/seeker/jobs/{jobId}/apply", jobId).with(user(priya)).with(csrf())
                        .param("resumeChoice", "PROFILE")
                        .param("coverLetter", "Excited to bring my Spring Boot experience to this role."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/16?submitted"));

        JobApplication application = jobApplicationRepository.findByJob_IdAndSeeker_Id(jobId, data.userId("priya@demo.local"))
                .orElseThrow();
        assertThat(application.getReference()).matches("APP-\\d{5}");
        assertThat(application.getReference()).isEqualTo("APP-00016");
        assertThat(application.getStatus().name()).isEqualTo("APPLIED");
        assertThat(application.getResumeStoredName()).isNotEqualTo(profileResumeStoredName);

        mockMvc.perform(get("/seeker/applications/{id}?submitted", application.getId()).with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Application submitted!")))
                .andExpect(content().string(containsString("Spring Boot Intern")))
                .andExpect(content().string(containsString("Acme Technologies")))
                .andExpect(content().string(containsString("APP-00016")));

        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/employer/applications").with(user(acme)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("APP-00016")));
    }

    // AC-S-F2-2: a second GET or POST for a job already applied to redirects to the
    // existing application with "You have already applied for this job."; the count stays
    // 1. The same holds for Data Analyst (A9) and the expired Python Backend Developer
    // (A14) - the duplicate check runs before the Live check (Section 6.4 S-F2 "Check
    // order"), so an expired job's existing application is still found first.
    @Test
    void duplicateApplicationBlocked() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long dataAnalystId = data.jobId("Data Analyst");
        Long a9Id = data.applicationId("priya@demo.local", "Data Analyst");

        mockMvc.perform(get("/seeker/jobs/{id}/apply", dataAnalystId).with(user(priya)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a9Id))
                .andExpect(flash().attribute("error", "You have already applied for this job."));

        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", dataAnalystId).with(user(priya)).with(csrf())
                        .param("resumeChoice", "PROFILE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a9Id))
                .andExpect(flash().attribute("error", "You have already applied for this job."));

        assertThat(jobApplicationRepository.countByJob_Id(dataAnalystId)).isEqualTo(3); // unchanged (J6: A7, A8, A9)

        Long pythonId = data.jobId("Python Backend Developer");
        Long a14Id = data.applicationId("priya@demo.local", "Python Backend Developer");
        mockMvc.perform(get("/seeker/jobs/{id}/apply", pythonId).with(user(priya)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a14Id))
                .andExpect(flash().attribute("error", "You have already applied for this job."));
    }

    // AC-S-F2-3 (file rules): a .txt file, a 3 MB PDF and a .pdf whose bytes start with MZ
    // each show the matching field error on resumeFile and create no application; an
    // employer opening the apply URL gets 403 (business rule 1: "URL zone: only
    // JOB_SEEKER").
    @Test
    void invalidFileRejectedNoApplication() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Spring Boot Intern");
        Long priyaId = data.userId("priya@demo.local");

        MockMultipartFile txtFile = new MockMultipartFile("resumeFile", "resume.txt", "text/plain", TestFiles.fakeContent());
        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", jobId).file(txtFile).with(user(priya)).with(csrf())
                        .param("resumeChoice", "UPLOAD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Only PDF, DOC or DOCX files are allowed.")));

        MockMultipartFile bigFile = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf",
                TestFiles.pdfBytesOfSize(3 * 1024 * 1024));
        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", jobId).file(bigFile).with(user(priya)).with(csrf())
                        .param("resumeChoice", "UPLOAD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("File is larger than 2 MB.")));

        MockMultipartFile fakePdf = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", TestFiles.exeBytes());
        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", jobId).file(fakePdf).with(user(priya)).with(csrf())
                        .param("resumeChoice", "UPLOAD"))
                .andExpect(status().isOk())
                // th:text HTML-escapes the apostrophe ("doesn&#39;t"), so the raw response
                // is checked against an apostrophe-free slice (Section 11.3 contract item
                // 9), the same pattern EmployerApplicationTest uses for its own note text.
                .andExpect(content().string(containsString("match its extension.")));

        assertThat(jobApplicationRepository.findByJob_IdAndSeeker_Id(jobId, priyaId)).isEmpty();

        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/seeker/jobs/{id}/apply", jobId).with(user(acme)))
                .andExpect(status().isForbidden());
    }

    // AC-S-F2-3 (resume-choice rule): Neha (no profile resume) choosing "Use my profile
    // resume" is rejected with ApplicationForm.RESUME_CHOICE_MESSAGE and no application is
    // created.
    @Test
    void profileChoiceWithoutResumeRejected() throws Exception {
        UserDetails neha = userDetailsService.loadUserByUsername("neha@demo.local");
        Long jobId = data.jobId("Spring Boot Intern");
        Long nehaId = data.userId("neha@demo.local");

        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", jobId).with(user(neha)).with(csrf())
                        .param("resumeChoice", "PROFILE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please upload a resume or choose your profile resume.")));

        assertThat(jobApplicationRepository.findByJob_IdAndSeeker_Id(jobId, nehaId)).isEmpty();
    }

    // AC-S-F1-3 (M5 half) + AC-S-F2-3 (Live rule): a direct POST for a job that is no
    // longer Live is refused with "This job is no longer accepting applications." and
    // creates nothing - first for a job hidden because its employer was just deactivated
    // (Karan, who has no applications, applying for Data Analyst), then for the expired
    // Python Backend Developer via a GET (Neha, who also has no applications).
    @Test
    void nonLiveJobRejected() throws Exception {
        User globex = userRepository.findById(data.userId("talent@globex.local")).orElseThrow();
        globex.setEnabled(false);
        userRepository.saveAndFlush(globex);

        UserDetails karan = userDetailsService.loadUserByUsername("karan@demo.local");
        Long dataAnalystId = data.jobId("Data Analyst");
        Long karanId = data.userId("karan@demo.local");

        MockMultipartFile pdf = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", TestFiles.pdfBytes());
        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", dataAnalystId).file(pdf).with(user(karan)).with(csrf())
                        .param("resumeChoice", "UPLOAD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/jobs"))
                .andExpect(flash().attribute("error", "This job is no longer accepting applications."));

        assertThat(jobApplicationRepository.findByJob_IdAndSeeker_Id(dataAnalystId, karanId)).isEmpty();

        UserDetails neha = userDetailsService.loadUserByUsername("neha@demo.local");
        Long pythonId = data.jobId("Python Backend Developer");
        mockMvc.perform(get("/seeker/jobs/{id}/apply", pythonId).with(user(neha)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/jobs"))
                .andExpect(flash().attribute("error", "This job is no longer accepting applications."));
    }
}
