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
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.support.TestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Resume files, Section 6.5.2/7.4 (G-8). Uses the profile-resume upload route for the
// type/content checks (the same FileStorageService.store() rules every upload route
// shares) and A1 (Priya's Java Developer application) for the copy/ownership checks.
class ResumeFileTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private SeekerProfileRepository seekerProfileRepository;

    // AC-G8-1: an .exe renamed to .pdf fails the magic-byte check; an empty file is
    // rejected before that check even runs.
    @Test
    void rejectsWrongTypeEmptyAndFakeContent() throws Exception {
        UserDetails karan = userDetailsService.loadUserByUsername("karan@demo.local"); // no resume yet

        MockMultipartFile fakePdf = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", TestFiles.exeBytes());
        mockMvc.perform(multipart("/seeker/profile/resume").file(fakePdf).with(user(karan)).with(csrf()))
                .andExpect(status().isOk())
                // th:text HTML-escapes the apostrophe ("doesn&#39;t"), so the raw response
                // is checked against an apostrophe-free slice (Section 11.3 contract item
                // 9), the same pattern EmployerApplicationTest uses for its own note text.
                .andExpect(content().string(containsString("match its extension.")));

        MockMultipartFile empty = new MockMultipartFile("resumeFile", "empty.pdf", "application/pdf", new byte[0]);
        mockMvc.perform(multipart("/seeker/profile/resume").file(empty).with(user(karan)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The file is empty.")));

        assertThat(seekerProfileRepository.findByUser_Id(data.userId("karan@demo.local")).orElseThrow()
                .getResumeStoredName()).isNull();
    }

    // AC-G8-2: after Priya replaces her profile resume, the copy already submitted with
    // A1 (Java Developer) is untouched - Employer downloads still see the original bytes
    // (Section 7.4 "Copies").
    @Test
    void applicationKeepsCopyAfterProfileReplace() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");
        String originalAppStoredName = jobApplicationRepository.findById(a1Id).orElseThrow().getResumeStoredName();

        MockMultipartFile newResume = new MockMultipartFile("resumeFile", "priya_new.pdf", "application/pdf", TestFiles.pdfBytes());
        mockMvc.perform(multipart("/seeker/profile/resume").file(newResume).with(user(priya)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Resume uploaded successfully."));

        JobApplication a1 = jobApplicationRepository.findById(a1Id).orElseThrow();
        assertThat(a1.getResumeStoredName()).isEqualTo(originalAppStoredName);

        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/employer/applications/{id}/resume", a1Id).with(user(acme)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    // AC-G8-3 (ownership): Globex cannot reach Acme's A1 resume copy (404); an anonymous
    // request is redirected to login, not served or errored.
    @Test
    void downloadsCheckOwnership() throws Exception {
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");

        UserDetails globex = userDetailsService.loadUserByUsername("talent@globex.local");
        mockMvc.perform(get("/employer/applications/{id}/resume", a1Id).with(user(globex)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/employer/applications/{id}/resume", a1Id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // AC-G8-3 (missing file): a row whose file is gone from disk shows the friendly flash
    // instead of a 500 or a raw IOException.
    @Test
    void missingFileShowsFlash() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long a9Id = data.applicationId("priya@demo.local", "Data Analyst");

        JobApplication a9 = jobApplicationRepository.findById(a9Id).orElseThrow();
        a9.setResumeStoredName("00000000-0000-0000-0000-000000000000.pdf");
        jobApplicationRepository.saveAndFlush(a9);

        mockMvc.perform(get("/seeker/applications/{id}/resume", a9Id).with(user(priya)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/applications/" + a9Id))
                .andExpect(flash().attribute("error", "The resume file could not be found."));
    }
}
