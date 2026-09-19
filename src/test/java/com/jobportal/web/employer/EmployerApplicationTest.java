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
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.UserRepository;
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
    @Autowired
    private UserRepository userRepository;

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

    // ==================== Bulk status change (new feature) ====================
    //
    // Decision this feature makes and these tests pin down: a mixed selection is applied
    // wherever the Section 5.6 matrix allows it and skipped (with a reason, never
    // silently) wherever it does not - not all-or-nothing. See
    // JobApplicationService.bulkChangeStatus for the full reasoning.

    // A4 (Sneha, Applied) and A2 (Rohan, Shortlisted) both legally move to Rejected; A6
    // (Sneha, Hired) and A15 (Sneha, Withdrawn) do not, because both are already final
    // (5.6: HIRED/WITHDRAWN allow no further transition). All four are still Acme's own,
    // so this is purely about legality, not ownership (see the cross-employer test below
    // for that). The two legal ones move AND the two illegal ones are named in the flash,
    // never dropped silently.
    @Test
    void bulkStatusChangeAppliesLegalRowsAndReportsSkippedOnesByName() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer"); // APPLIED
        Long a2Id = data.applicationId("rohan@demo.local", "Java Developer"); // SHORTLISTED
        Long a6Id = data.applicationId("sneha@demo.local", "Frontend Developer"); // HIRED
        Long a15Id = data.applicationId("sneha@demo.local", "Python Backend Developer"); // WITHDRAWN
        String expectedWarning = "2 application(s) updated to Rejected. 2 skipped (not a legal change from their "
                + "current status): " + String.format("APP-%05d", a6Id) + " (currently Hired); "
                + String.format("APP-%05d", a15Id) + " (currently Withdrawn).";

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a4Id.toString(), a2Id.toString(), a6Id.toString(), a15Id.toString())
                        .param("newStatus", "REJECTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications"))
                .andExpect(flash().attribute("warning", expectedWarning));

        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(jobApplicationRepository.findById(a2Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(jobApplicationRepository.findById(a6Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.HIRED);
        assertThat(jobApplicationRepository.findById(a15Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    // Every selected row allows the change (Applied and Under review both legally move to
    // Shortlisted, 5.6) - a clean "success" flash, nothing skipped.
    @Test
    void bulkStatusChangeAllLegalIsPlainSuccess() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer"); // APPLIED
        Long a5Id = data.applicationId("priya@demo.local", "Frontend Developer"); // UNDER_REVIEW

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a4Id.toString(), a5Id.toString())
                        .param("newStatus", "SHORTLISTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications"))
                .andExpect(flash().attribute("success", "2 application(s) updated to Shortlisted."))
                .andExpect(flash().attribute("error", (Object) null))
                .andExpect(flash().attribute("warning", (Object) null)); // no mixed-outcome flash alongside it

        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.SHORTLISTED);
        assertThat(jobApplicationRepository.findById(a5Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.SHORTLISTED);

        // Every legal bulk change still goes through changeStatus()/recordStatusChange
        // (11.3-style contract: one path only), so it still writes the usual
        // ApplicationStatusChange timeline row - checked here for A4.
        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatusChangedAt()).isNotNull();
    }

    // Neither selected row allows the change (both already final) - nothing moves, and
    // the "error" flash names both instead of showing a bare "0 updated".
    @Test
    void bulkStatusChangeAllIllegalIsErrorWithNoSideEffect() throws Exception {
        UserDetails employer = acme();
        Long a6Id = data.applicationId("sneha@demo.local", "Frontend Developer"); // HIRED
        Long a15Id = data.applicationId("sneha@demo.local", "Python Backend Developer"); // WITHDRAWN
        String expectedError = "No applications were changed to Rejected - that change is not legal from any of the "
                + "selected applications' current status. Skipped: " + String.format("APP-%05d", a6Id)
                + " (currently Hired); " + String.format("APP-%05d", a15Id) + " (currently Withdrawn).";

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a6Id.toString(), a15Id.toString())
                        .param("newStatus", "REJECTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", expectedError));

        assertThat(jobApplicationRepository.findById(a6Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.HIRED);
        assertThat(jobApplicationRepository.findById(a15Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    // The security-critical case: Acme selects one of its own applications AND one of
    // Globex's (A7, Data Analyst). Acme's own row still moves; Globex's is reported as
    // "not found" (same ownership query as getForEmployer, Section 4.5) and, above all,
    // is NEVER written to - an employer must never change another employer's data, which
    // would be strictly worse than the export-side leak Section 4.5 already guards
    // against.
    @Test
    void bulkStatusChangeNeverTouchesAnotherEmployersApplication() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer"); // APPLIED, Acme's own
        Long a7Id = data.applicationId("arjun@demo.local", "Data Analyst"); // SHORTLISTED, Globex's
        ApplicationStatus a7StatusBefore = jobApplicationRepository.findById(a7Id).orElseThrow().getStatus();

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a4Id.toString(), a7Id.toString())
                        .param("newStatus", "REJECTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("warning", "1 application(s) updated to Rejected. 1 skipped (not a legal "
                        + "change from their current status): Application " + a7Id + " (not found)."));

        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(jobApplicationRepository.findById(a7Id).orElseThrow().getStatus()).isEqualTo(a7StatusBefore);
    }

    // AC-style guard clauses: neither a missing selection nor a missing status ever
    // reaches the service - both are refused with their own plain-English flash, per
    // Section 7.2's "the employer must end up understanding exactly what happened".
    @Test
    void bulkStatusChangeRequiresAtLeastOneApplicationSelected() throws Exception {
        UserDetails employer = acme();

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("newStatus", "REJECTED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications"))
                .andExpect(flash().attribute("error", "Select at least one application before applying a bulk status change."));
    }

    @Test
    void bulkStatusChangeRequiresAStatusToBeChosen() throws Exception {
        UserDetails employer = acme();
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a4Id.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications"))
                .andExpect(flash().attribute("error", "Choose a status to apply to the selected applications."));

        assertThat(jobApplicationRepository.findById(a4Id).orElseThrow().getStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    // The redirect after a bulk action must return to the exact filtered/paged list the
    // employer was on (returnJobId/returnStatus/returnPage, mirroring that page's own
    // jobId/status/page.number model attributes) rather than a generic "back" - see
    // EmployerApplicationController#listRedirect.
    @Test
    void bulkStatusChangeRedirectsBackToTheFilteredPageItWasSubmittedFrom() throws Exception {
        UserDetails employer = acme();
        Long javaDeveloperJobId = data.jobId("Java Developer");
        Long a4Id = data.applicationId("sneha@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/bulk-status").with(user(employer)).with(csrf())
                        .param("applicationIds", a4Id.toString())
                        .param("newStatus", "UNDER_REVIEW")
                        .param("returnJobId", javaDeveloperJobId.toString())
                        .param("returnStatus", "ACTIVE")
                        .param("returnPage", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications?jobId=" + javaDeveloperJobId + "&status=ACTIVE&page=2"));
    }

    // ==================== CSV export (new feature) ====================

    // The file matches what the employer is looking at (own applications only, Section
    // 4.5) and is offered as a download, never rendered inline.
    @Test
    void exportContainsOnlyThisEmployersOwnApplicationsAsCsv() throws Exception {
        UserDetails employer = acme();

        String body = mockMvc.perform(get("/employer/applications/export").with(user(employer)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"Reference\",\"Candidate\",\"Email\",\"Job\",\"Applied on\",\"Status\"");
        for (String ref : List.of("APP-00001", "APP-00002", "APP-00003", "APP-00004", "APP-00005", "APP-00006",
                "APP-00014", "APP-00015")) {
            assertThat(body).contains(ref);
        }
        for (String ref : List.of("APP-00007", "APP-00008", "APP-00009", "APP-00010", "APP-00011", "APP-00012",
                "APP-00013")) {
            assertThat(body).doesNotContain(ref);
        }
        // One full row, spot-checked (A6: Sneha Iyer, Frontend Developer, Hired).
        assertThat(body).contains("\"APP-00006\",\"Sneha Iyer\",\"sneha@demo.local\",\"Frontend Developer\",");
        assertThat(body).contains("\"Hired\"");
    }

    // Same status filter vocabulary as the list page (Section 7.9): only HIRED
    // applications come back, which among Acme's own is exactly A6.
    @Test
    void exportRespectsTheStatusFilter() throws Exception {
        UserDetails employer = acme();

        String body = mockMvc.perform(get("/employer/applications/export").param("status", "HIRED").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("APP-00006");
        for (String ref : List.of("APP-00001", "APP-00002", "APP-00003", "APP-00004", "APP-00005", "APP-00014",
                "APP-00015")) {
            assertThat(body).doesNotContain(ref);
        }
    }

    // A jobId naming one of Globex's jobs is ignored the same way list() ignores it
    // (Section 4.5 "no leak", ownJobIdOrNull) - Acme still gets all 8 of its own rows,
    // never Globex's.
    @Test
    void exportIgnoresAForeignJobIdFilterRatherThanLeaking() throws Exception {
        UserDetails employer = acme();
        Long dataAnalystId = data.jobId("Data Analyst"); // Globex's job (J6)

        String body = mockMvc.perform(get("/employer/applications/export").param("jobId", dataAnalystId.toString())
                        .with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String ref : List.of("APP-00001", "APP-00002", "APP-00003", "APP-00004", "APP-00005", "APP-00006",
                "APP-00014", "APP-00015")) {
            assertThat(body).contains(ref);
        }
        assertThat(body).doesNotContain("APP-00007");
    }

    // OWASP "CSV Injection" end to end: a candidate's own full name is attacker-controlled
    // text that reaches this file, so a name opening with a formula-trigger character
    // must come back with the neutralising leading apostrophe (web.support.Csv), not
    // opened as a formula by Excel.
    @Test
    void exportNeutralisesACandidateNameThatLooksLikeAFormula() throws Exception {
        UserDetails employer = acme();
        User priya = userRepository.findById(data.userId("priya@demo.local")).orElseThrow();
        priya.setFullName("=HYPERLINK(\"http://evil.example\",\"click\")");
        userRepository.saveAndFlush(priya);

        String body = mockMvc.perform(get("/employer/applications/export").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"'=HYPERLINK(");
        assertThat(body).doesNotContain("\"=HYPERLINK(");
    }

    private UserDetails acme() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }
}
