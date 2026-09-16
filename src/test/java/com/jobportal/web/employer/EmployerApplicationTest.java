package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Employer application review (Section 6.3 E-F2/E-D2). Acme's 8 applications are A1-A6,
// A14, A15 (Java Developer, Frontend Developer, Python Backend Developer - Section 13.5);
// everything else belongs to Globex. Seed ids equal seed codes on a fresh database
// (13.1), so "APP-000NN" (JobApplication#getReference) is checked directly wherever the
// plan itself names an application by its A-number.
class EmployerApplicationTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;

    // AC-E-F2-1: Acme moves A4 (Sneha Iyer, Java Developer) from Applied to Shortlisted
    // with a note; the flash appears, the note is stored and shown back on the employer
    // page. Job seeker pages do not exist yet (M5), so the "Sneha's list shows Shortlisted
    // with the Updated badge" half of this AC is checked through the same
    // isUpdatedForSeeker() flag the seeker pages will read once M5 ships.
    @Test
    void shortlistWithNoteVisibleToSeeker() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer");
        String note = "Strong portfolio, we'd like to talk.";

        mockMvc.perform(post("/employer/applications/{id}/status", a4Id).with(user(employer)).with(csrf())
                        .param("status", "SHORTLISTED")
                        .param("noteToCandidate", note))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications/" + a4Id))
                .andExpect(flash().attribute("success", "Status for Sneha Iyer updated to Shortlisted."));

        JobApplication application = jobApplicationRepository.findById(a4Id).orElseThrow();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.SHORTLISTED);
        assertThat(application.isUpdatedForSeeker()).isTrue();

        // The rendered page HTML-escapes the note's apostrophe (th:text, Section 11.3
        // contract item 9: "user text only via th:text"), so the raw response is checked
        // against an apostrophe-free slice of it rather than the note verbatim.
        mockMvc.perform(get("/employer/applications/{id}", a4Id).with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Shortlisted")))
                .andExpect(content().string(containsString("like to talk.")));
    }

    // AC-E-F2-2: an out-of-order transition (Applied straight to Interview) is refused
    // with the exact message; any status change for the withdrawn A15 is refused too -
    // WITHDRAWN.allowedNext() is empty (5.6), so nothing is a legal "next" for it.
    @Test
    void invalidTransitionRejected() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer"); // APPLIED
        Long a15Id = data.applicationId("sneha@demo.local", "Python Backend Developer"); // WITHDRAWN

        mockMvc.perform(post("/employer/applications/{id}/status", a4Id).with(user(employer)).with(csrf())
                        .param("status", "INTERVIEW"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Can't change status from Applied to Interview."));
        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.APPLIED);

        mockMvc.perform(post("/employer/applications/{id}/status", a15Id).with(user(employer)).with(csrf())
                        .param("status", "REJECTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Can't change status from Withdrawn to Rejected."));
        assertThat(jobApplicationRepository.findById(a15Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    // AC-E-F2-2 (last clause): A1 (Priya, Interview - Section 13.5) offers only Hired and
    // Rejected in the "New status" select - ApplicationStatus.employerOptions() (5.6).
    @Test
    void dropdownOffersOnlyAllowedStatuses() throws Exception {
        UserDetails employer = acme();
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer"); // INTERVIEW

        String body = mockMvc.perform(get("/employer/applications/{id}", a1Id).with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int selectStart = body.indexOf("id=\"status\"");
        int selectEnd = body.indexOf("</select>", selectStart);
        String select = body.substring(selectStart, selectEnd);
        assertThat(select).contains(">Hired<", ">Rejected<");
        assertThat(select).doesNotContain(">Interview<", ">Applied<", ">Under review<", ">Shortlisted<", ">Withdrawn<");
    }

    // AC-E-F2-3: the private note is shown to the employer that wrote it and never
    // logged (11.3 contract item 5 / Section 6.3 E-F2 business rule 6), so it can never
    // surface in the activity feed any admin or other employer can read. Job seeker
    // pages do not exist yet (M5); the seeker-facing half of this AC will be
    // ResumeFileTest/ApplicationTrackingTest's job once M5 ships.
    @Test
    void internalNoteNeverShownToSeeker() throws Exception {
        UserDetails employer = acme();
        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");
        String note = "Salary expectation above budget";
        long activityBefore = activityLogRepository.count();

        mockMvc.perform(post("/employer/applications/{id}/internal-note", a1Id).with(user(employer)).with(csrf())
                        .param("internalNote", note))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications/" + a1Id))
                .andExpect(flash().attribute("success", "Private note saved."));

        assertThat(jobApplicationRepository.findById(a1Id).orElseThrow().getInternalNote()).isEqualTo(note);
        mockMvc.perform(get("/employer/applications/{id}", a1Id).with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(note)));

        assertThat(activityLogRepository.count()).isEqualTo(activityBefore);
        assertThat(activityLogRepository.findAll())
                .noneMatch(l -> l.getDescription() != null && l.getDescription().contains(note));
    }

    // AC-E-D2-1: Acme's list is exactly its 8 applications (A1-A6, A14, A15); a Globex
    // application id is a 404; its PDF resume copy opens inline.
    @Test
    void employerSeesOnlyOwnApplicants() throws Exception {
        UserDetails employer = acme();

        String body = mockMvc.perform(get("/employer/applications").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String ref : List.of("APP-00001", "APP-00002", "APP-00003", "APP-00004", "APP-00005", "APP-00006",
                "APP-00014", "APP-00015")) {
            assertThat(body).contains(ref);
        }
        for (String ref : List.of("APP-00007", "APP-00008", "APP-00009", "APP-00010", "APP-00011", "APP-00012",
                "APP-00013")) {
            assertThat(body).doesNotContain(ref);
        }
        Long a7Id = data.applicationId("arjun@demo.local", "Data Analyst"); // Globex's
        mockMvc.perform(get("/employer/applications/{id}", a7Id).with(user(employer)))
                .andExpect(status().isNotFound());

        Long a1Id = data.applicationId("priya@demo.local", "Java Developer");
        mockMvc.perform(get("/employer/applications/{id}/resume", a1Id).with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    // AC-E-D2-1: a jobId naming one of Globex's jobs is ignored (Section 4.5, "no leak"),
    // so Acme still sees all 8 of its own applications.
    @Test
    void foreignJobIdFilterIgnored() throws Exception {
        UserDetails employer = acme();
        Long dataAnalystId = data.jobId("Data Analyst"); // Globex's job (J6)

        String body = mockMvc.perform(get("/employer/applications").param("jobId", dataAnalystId.toString())
                        .with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String ref : List.of("APP-00001", "APP-00002", "APP-00003", "APP-00004", "APP-00005", "APP-00006",
                "APP-00014", "APP-00015")) {
            assertThat(body).contains(ref);
        }
    }

    private UserDetails acme() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }
}
