package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Interview;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.InterviewMode;
import com.jobportal.domain.enums.InterviewStatus;
import com.jobportal.repository.InterviewRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.service.InterviewService;
import com.jobportal.service.MailMessage;
import com.jobportal.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Interview scheduling, built on top of the existing ApplicationStatus.INTERVIEW stage
// (Section 5.6). Every test here reuses the SEEDED applications of Section 13.5 and never
// inserts a JobApplication of its own - not for speed, but because H2 does not roll an
// identity counter back with the transaction, so a single inserted-then-rolled-back
// application would shift the next generated id and break JobApplicationTest's hard-coded
// "APP-00016" in a completely unrelated file (Section 12.1 documents the same trap). The
// two seeded rows this class leans on are:
//
//   A1  priya@demo.local -> "Java Developer" (Acme)  ... already at INTERVIEW
//   A2  rohan@demo.local -> "Java Developer" (Acme)  ... at SHORTLISTED
//
// Interview rows themselves ARE inserted freely: nothing anywhere hard-codes an interview
// id, and the table is new, so its counter belongs to this feature alone.
//
// The clock is FixedClockConfig's 16 Sep 2026, 10:00 Asia/Kolkata (Section 12.1), which is
// what makes "a week on Wednesday" and "yesterday" stable strings below rather than
// arithmetic on the real date.
class InterviewSchedulingTest extends IntegrationTestBase {

    // Comfortably after the fixed clock's "now", and comfortably inside the 12-month
    // window InterviewService allows.
    private static final String FUTURE_DATE = "2026-09-23";
    private static final String FUTURE_TIME = "15:30";
    private static final LocalDateTime FUTURE_SLOT = LocalDateTime.of(2026, 9, 23, 15, 30);

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    // ==================== Scheduling ====================

    // The happy path, end to end: an application ALREADY at the Interview stage gets an
    // interview, the row lands with the details as typed, and the candidate is emailed.
    @Test
    void employerSchedulesInterviewForApplicationAtInterviewStage() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(acme())).with(csrf())
                        .param("date", FUTURE_DATE)
                        .param("time", FUTURE_TIME)
                        .param("mode", "VIDEO")
                        .param("location", "https://meet.example.com/abc-defg-hij")
                        .param("notes", "A 45 minute technical discussion."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/applications/" + a1))
                .andExpect(flash().attribute("success", containsString("Interview scheduled for")));

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(interview.getMode()).isEqualTo(InterviewMode.VIDEO);
        assertThat(interview.getScheduledAt()).isEqualTo(FUTURE_SLOT);
        assertThat(interview.getLocation()).isEqualTo("https://meet.example.com/abc-defg-hij");
        assertThat(interview.getNotes()).isEqualTo("A 45 minute technical discussion.");
        assertThat(interview.getRescheduleCount()).isZero();
        assertThat(interview.getPreviousScheduledAt()).isNull();

        // The status is untouched by scheduling - this feature is attached to the stage,
        // it does not drive it (Interview's class comment, InterviewService's header).
        assertThat(jobApplicationRepository.findById(a1).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.INTERVIEW);

        MailMessage email = onlyEmailWithSubjectContaining("Interview scheduled");
        assertThat(email.to()).isEqualTo("priya@demo.local");
        assertThat(email.body()).contains(interview.getWhenText())
                .contains("Video call")
                .contains("Joining link: https://meet.example.com/abc-defg-hij")
                .contains("A 45 minute technical discussion.");
    }

    // The employer page's own branching, which decides whether the feature is reachable at
    // all: the form appears exactly when the application is at the Interview stage, and the
    // pipeline's own instruction appears when it is not. Worth a rendering test rather than
    // trusting the POST tests above - a POST succeeds whether or not any page ever offered
    // the form.
    @Test
    void schedulingFormAppearsOnlyOnceTheApplicationIsAtTheInterviewStage() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer"); // INTERVIEW
        Long a2 = data.applicationId("rohan@demo.local", "Java Developer"); // SHORTLISTED

        mockMvc.perform(get("/employer/applications/{id}", a1).with(user(acme())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Schedule an interview")))
                .andExpect(content().string(containsString("/employer/applications/" + a1 + "/interview")))
                // The zone the employer's typed time will be read in, said out loud next to
                // the field rather than left to be discovered by the candidate.
                .andExpect(content().string(containsString("Times are in Asia/Kolkata.")));

        mockMvc.perform(get("/employer/applications/{id}", a2).with(user(acme())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Move this candidate to the Interview stage")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Times are in Asia/Kolkata."))));
    }

    // Once something is arranged the same card switches to "change it or call it off", with
    // the form pre-filled from the stored row so a reschedule is an edit rather than a
    // re-typing (which is how a joining link gets lost).
    @Test
    void scheduledInterviewSwitchesTheCardToRescheduleAndCancel() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "VIDEO", "https://meet.example.com/abc-defg-hij", null);

        mockMvc.perform(get("/employer/applications/{id}", a1).with(user(acme())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reschedule or update")))
                .andExpect(content().string(containsString("/employer/applications/" + a1 + "/interview/reschedule")))
                .andExpect(content().string(containsString("/employer/applications/" + a1 + "/interview/cancel")))
                // The pre-filled values must be in the format the controls accept, not the
                // locale's short style - see InterviewForm's @DateTimeFormat comment for
                // the bug this pins down.
                .andExpect(content().string(containsString("value=\"" + FUTURE_DATE + "\"")))
                // "15:30:00", not "15:30": ISO.TIME prints seconds, and hh:mm:ss is a valid
                // time string that <input type="time"> accepts and displays as 15:30 under
                // the control's default one-minute step. Asserted as rendered rather than
                // loosened to a prefix, so a future change to the format is a visible
                // decision instead of a silent one.
                .andExpect(content().string(containsString("value=\"15:30:00\"")))
                .andExpect(content().string(containsString("https://meet.example.com/abc-defg-hij")));
    }

    // The zone decision, asserted rather than described: the row stores the injected
    // Clock's zone (not the machine's), and the rendered time names it, so the employer and
    // the candidate cannot read the same digits as two different moments. Deliberately
    // checks the id "Asia/Kolkata" and not only the abbreviation, since "IST" alone is
    // genuinely ambiguous.
    @Test
    void storedInterviewCarriesTheSiteZoneAndShowsIt() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", null, null);

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(interview.getWhenText()).startsWith("23 Sep 2026, 3:30 PM").endsWith("(Asia/Kolkata)");

        mockMvc.perform(get("/employer/applications/{id}", a1).with(user(acme())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Asia/Kolkata")));
    }

    // The core rule of the whole feature: scheduling is refused for an application that has
    // not reached the Interview stage, and refusing it does NOT quietly move the status
    // there instead. A2 is at SHORTLISTED, one legal step away - the closest case there is.
    @Test
    void schedulingRefusedBeforeTheInterviewStageAndDoesNotMoveTheStatus() throws Exception {
        Long a2 = data.applicationId("rohan@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a2).with(user(acme())).with(csrf())
                        .param("date", FUTURE_DATE)
                        .param("time", FUTURE_TIME)
                        .param("mode", "PHONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", InterviewService.NOT_AT_INTERVIEW_STAGE_MESSAGE));

        assertThat(interviewRepository.findByApplication_Id(a2)).isEmpty();
        assertThat(jobApplicationRepository.findById(a2).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.SHORTLISTED);
        assertThat(mailSent.sent()).isEmpty();
    }

    // The past-date rule. Worth its own test because it is the one validation whose failure
    // lands entirely on the candidate: nothing about an interview emailed for yesterday is
    // recoverable by them.
    @Test
    void schedulingInThePastIsRefused() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(acme())).with(csrf())
                        .param("date", "2026-09-15") // the fixed clock is 16 Sep 2026, 10:00
                        .param("time", "09:00")
                        .param("mode", "PHONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", InterviewService.PAST_MESSAGE));

        assertThat(interviewRepository.findByApplication_Id(a1)).isEmpty();
        assertThat(mailSent.sent()).isEmpty();
    }

    // The mirror-image slip: "2206" for "2026" is in the future and would otherwise sail
    // through, emailing the candidate an invitation for the twenty-third century.
    @Test
    void schedulingAbsurdlyFarAheadIsRefused() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(acme())).with(csrf())
                        .param("date", "2206-09-23")
                        .param("time", FUTURE_TIME)
                        .param("mode", "PHONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", InterviewService.TOO_FAR_AHEAD_MESSAGE));

        assertThat(interviewRepository.findByApplication_Id(a1)).isEmpty();
    }

    // A field the employer can fix re-renders the page with the error attached, rather than
    // flashing and redirecting (Section 7.2) - a video interview with no joining link is an
    // instruction the candidate cannot act on, and the fix is one box away.
    @Test
    void videoInterviewWithoutAJoiningLinkIsAFieldError() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(acme())).with(csrf())
                        .param("date", FUTURE_DATE)
                        .param("time", FUTURE_TIME)
                        .param("mode", "VIDEO")
                        .param("location", "  "))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("interviewForm", "locationProvidedForMode"));

        assertThat(interviewRepository.findByApplication_Id(a1)).isEmpty();
        assertThat(mailSent.sent()).isEmpty();
    }

    // A phone interview may legitimately say nothing about where - "we will call the number
    // on your profile" is a complete instruction - so the same blank field is accepted here
    // (InterviewMode.isDetailRequired).
    @Test
    void phoneInterviewWithoutADetailIsAccepted() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", "  ", "   ");

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getMode()).isEqualTo(InterviewMode.PHONE);
        // Blank-to-null, so every page and every email can guard on `!= null` alone.
        assertThat(interview.getLocation()).isNull();
        assertThat(interview.getNotes()).isNull();
    }

    // Section 4.5: another employer's application is a 404, not a 403 and not a leak - the
    // same ownership rule every other action on this page already obeys, reached here
    // through InterviewService's one call into JobApplicationService#getForEmployer.
    @Test
    void anotherEmployerCannotScheduleOnSomebodyElsesApplication() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(globex())).with(csrf())
                        .param("date", FUTURE_DATE)
                        .param("time", FUTURE_TIME)
                        .param("mode", "PHONE"))
                .andExpect(status().isNotFound());

        assertThat(interviewRepository.findByApplication_Id(a1)).isEmpty();
    }

    // ==================== Rescheduling ====================

    // A genuine move: the old slot is remembered, the count goes up, and the email names
    // the time the candidate has to find and delete from their calendar.
    @Test
    void reschedulingRecordsTheOldSlotAndTellsTheCandidateItMoved() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "VIDEO", "https://meet.example.com/abc", null);
        String originalWhen = interviewRepository.findByApplication_Id(a1).orElseThrow().getWhenText();
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/interview/reschedule", a1).with(user(acme())).with(csrf())
                        .param("date", "2026-09-24")
                        .param("time", "11:00")
                        .param("mode", "VIDEO")
                        .param("location", "https://meet.example.com/abc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString("Interview updated")));

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 11, 0));
        assertThat(interview.getPreviousScheduledAt()).isEqualTo(FUTURE_SLOT);
        assertThat(interview.getRescheduleCount()).isEqualTo(1);
        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);

        MailMessage email = onlyEmailWithSubjectContaining("Interview rescheduled");
        assertThat(email.body()).contains("moved from " + originalWhen).contains(interview.getWhenText());
    }

    // Changing only the joining link must NOT tell someone their appointment has moved -
    // whether this counts as a reschedule is decided from the data, not from which handler
    // was called (InterviewService#reschedule).
    @Test
    void correctingOnlyTheLinkDoesNotClaimTheTimeChanged() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "VIDEO", "https://meet.example.com/typo", null);
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/interview/reschedule", a1).with(user(acme())).with(csrf())
                        .param("date", FUTURE_DATE)
                        .param("time", FUTURE_TIME)
                        .param("mode", "VIDEO")
                        .param("location", "https://meet.example.com/correct"))
                .andExpect(status().is3xxRedirection());

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getLocation()).isEqualTo("https://meet.example.com/correct");
        assertThat(interview.getPreviousScheduledAt()).isNull();
        assertThat(interview.getRescheduleCount()).isZero();

        MailMessage email = onlyEmailWithSubjectContaining("Interview details updated");
        assertThat(email.body()).contains("The time has not changed.").doesNotContain("moved from");
    }

    // ==================== Cancelling ====================

    // The row is KEPT, not deleted: a candidate who has already blocked out the slot needs
    // to be told it is off, and an empty space where the appointment used to be tells them
    // nothing.
    @Test
    void cancellingKeepsTheRowWithItsReasonAndEmailsTheCandidate() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "ON_SITE", "Level 4, 12 MG Road, Bengaluru", null);
        String originalWhen = interviewRepository.findByApplication_Id(a1).orElseThrow().getWhenText();
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/interview/cancel", a1).with(user(acme())).with(csrf())
                        .param("reason", "The panel is unavailable that week."))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString("Interview cancelled")));

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
        assertThat(interview.getCancelledAt()).isNotNull();
        assertThat(interview.getCancellationReason()).isEqualTo("The panel is unavailable that week.");

        MailMessage email = onlyEmailWithSubjectContaining("Interview cancelled");
        assertThat(email.to()).isEqualTo("priya@demo.local");
        assertThat(email.body()).contains(originalWhen).contains("The panel is unavailable that week.");
    }

    // After a cancellation the next action is a NEW arrangement, not a revival of the old
    // one: uk_interview_application allows a single row, so it is reused, but the
    // reschedule bookkeeping is cleared and the candidate gets the plain "scheduled" email
    // (they have nothing left in their calendar to correct).
    @Test
    void schedulingAgainAfterACancellationStartsCleanOnTheSameRow() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", null, null);
        mockMvc.perform(post("/employer/applications/{id}/interview/cancel", a1).with(user(acme())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        Long rowId = interviewRepository.findByApplication_Id(a1).orElseThrow().getId();
        mailSent.clear();

        schedule(a1, "2026-09-30", "10:00", "ON_SITE", "Level 4, 12 MG Road, Bengaluru", null);

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getId()).isEqualTo(rowId);
        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(interview.getCancelledAt()).isNull();
        assertThat(interview.getCancellationReason()).isNull();
        assertThat(interview.getRescheduleCount()).isZero();
        assertThat(interview.getPreviousScheduledAt()).isNull();

        assertThat(onlyEmailWithSubjectContaining("Interview scheduled")).isNotNull();
    }

    // Two interviews for one application is a database-level impossibility
    // (uk_interview_application), so the service refuses the second attempt with a message
    // that names the action the employer actually wants.
    @Test
    void schedulingTwiceWithoutCancellingIsRefused() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", null, null);

        mockMvc.perform(post("/employer/applications/{id}/interview", a1).with(user(acme())).with(csrf())
                        .param("date", "2026-09-30")
                        .param("time", "10:00")
                        .param("mode", "PHONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", InterviewService.ALREADY_SCHEDULED_MESSAGE));

        assertThat(interviewRepository.findByApplication_Id(a1).orElseThrow().getScheduledAt())
                .isEqualTo(FUTURE_SLOT);
    }

    // ==================== What a final status does to an interview ====================

    // The case the whole auto-cancel rule exists for. A rejected candidate must not be left
    // holding a live appointment - and the failure would be silent, since nothing errors,
    // they simply turn up. Both facts are emailed: the status change, and the interview.
    @Test
    void rejectingTheApplicationCancelsAnUpcomingInterviewAndSaysSo() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "VIDEO", "https://meet.example.com/abc", null);
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/status", a1).with(user(acme())).with(csrf())
                        .param("status", "REJECTED"))
                .andExpect(status().is3xxRedirection());

        Interview interview = interviewRepository.findByApplication_Id(a1).orElseThrow();
        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
        assertThat(interview.getCancellationReason()).isEqualTo(InterviewService.CLOSED_APPLICATION_REASON);

        // One email for each fact - a candidate who skims "not selected" still has to act
        // on "Thursday is off".
        assertThat(subjects()).anyMatch(s -> s.contains("Update on your application"));
        assertThat(subjects()).anyMatch(s -> s.contains("Interview cancelled"));
    }

    // The other half of the rule, and the reason it is isUpcoming() rather than the status
    // alone: an interview whose time has passed HAPPENED, and a hire is very often its
    // result. Rewriting it to "Cancelled" would falsify the candidate's own record of it.
    //
    // The past row is written straight through the repository because the service refuses
    // to CREATE one in the past - which is the correct behaviour under test elsewhere. This
    // inserts an Interview, never a JobApplication, so no identity counter that any other
    // test depends on moves.
    @Test
    void hiringLeavesAnAlreadyPastInterviewAloneAsHistory() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        Interview past = pastInterviewFor(a1);
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/status", a1).with(user(acme())).with(csrf())
                        .param("status", "HIRED"))
                .andExpect(status().is3xxRedirection());

        Interview reloaded = interviewRepository.findById(past.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(reloaded.getCancelledAt()).isNull();
        assertThat(subjects()).noneMatch(s -> s.contains("Interview cancelled"));
    }

    // A stored interview naturally becomes past and must still display correctly - no
    // sweep, no rewrite, the same fields rendered with different words around them. The
    // candidate's own page is where that matters, so it is checked there.
    @Test
    void anInterviewWhoseTimeHasPassedStillDisplaysOnTheCandidatesPage() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        Interview past = pastInterviewFor(a1);

        mockMvc.perform(get("/seeker/applications/{id}", a1).with(user(priya())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interview date passed")))
                .andExpect(content().string(containsString(past.getWhenText())));
    }

    // Once an application is final there is nothing legitimate left to do to its interview,
    // so all three actions are refused - otherwise the page would still offer "Cancel" on a
    // rejected candidate's finished interview and emailing them about it days later.
    @Test
    void interviewActionsAreRefusedOnceTheApplicationIsFinal() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        pastInterviewFor(a1);
        mockMvc.perform(post("/employer/applications/{id}/status", a1).with(user(acme())).with(csrf())
                        .param("status", "HIRED"))
                .andExpect(status().is3xxRedirection());
        mailSent.clear();

        mockMvc.perform(post("/employer/applications/{id}/interview/cancel", a1).with(user(acme())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", InterviewService.NOT_AT_INTERVIEW_STAGE_MESSAGE));

        assertThat(interviewRepository.findByApplication_Id(a1).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(mailSent.sent()).isEmpty();
    }

    // ==================== What the candidate sees ====================

    // Both candidate-facing surfaces, in one test because they must agree: the detail page
    // and the tracking list show the SAME string, produced once by Interview#getWhenText.
    @Test
    void candidateSeesTheInterviewOnBothTheirDetailAndTrackingPages() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "ON_SITE", "Level 4, 12 MG Road, Bengaluru", "Ask for reception.");
        String whenText = interviewRepository.findByApplication_Id(a1).orElseThrow().getWhenText();

        mockMvc.perform(get("/seeker/applications/{id}", a1).with(user(priya())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interview scheduled")))
                .andExpect(content().string(containsString(whenText)))
                .andExpect(content().string(containsString("Level 4, 12 MG Road, Bengaluru")))
                .andExpect(content().string(containsString("Ask for reception.")));

        mockMvc.perform(get("/seeker/applications").with(user(priya())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(whenText)));
    }

    // A cancellation has to reach the candidate, not just disappear from their page - with
    // the employer's reason, which is the difference between "something changed" and "here
    // is what happened".
    @Test
    void candidateSeesACancelledInterviewWithItsReason() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", "+91 80 4000 1234", null);
        mockMvc.perform(post("/employer/applications/{id}/interview/cancel", a1).with(user(acme())).with(csrf())
                        .param("reason", "The panel is unavailable that week."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/seeker/applications/{id}", a1).with(user(priya())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interview cancelled")))
                .andExpect(content().string(containsString("The panel is unavailable that week.")));
    }

    // A candidate must never see another candidate's interview. The interview is only ever
    // reached through an application the viewer already owns (getForSeeker), so this is the
    // existing ownership rule being confirmed to cover the new card too.
    @Test
    void anotherCandidateCannotSeeTheInterview() throws Exception {
        Long a1 = data.applicationId("priya@demo.local", "Java Developer");
        schedule(a1, FUTURE_DATE, FUTURE_TIME, "PHONE", null, null);

        mockMvc.perform(get("/seeker/applications/{id}", a1).with(user(rohan())))
                .andExpect(status().isNotFound());
    }

    // ==================== helpers ====================

    private void schedule(Long applicationId, String date, String time, String mode, String location, String notes)
            throws Exception {
        var request = post("/employer/applications/{id}/interview", applicationId)
                .with(user(acme())).with(csrf())
                .param("date", date)
                .param("time", time)
                .param("mode", mode);
        if (location != null) {
            request = request.param("location", location);
        }
        if (notes != null) {
            request = request.param("notes", notes);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
    }

    // A SCHEDULED interview whose time has already gone by - the state a real row reaches
    // simply by being left alone, and the one the service will not create directly.
    private Interview pastInterviewFor(Long applicationId) {
        JobApplication application = jobApplicationRepository.findById(applicationId).orElseThrow();
        Interview interview = new Interview();
        interview.setApplication(application);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setMode(InterviewMode.ON_SITE);
        interview.setScheduledAt(LocalDateTime.of(2026, 9, 14, 11, 0)); // two days before the fixed clock
        interview.setTimeZone("Asia/Kolkata");
        interview.setLocation("Level 4, 12 MG Road, Bengaluru");
        interview.setRescheduleCount(0);
        interview.setCreatedAt(LocalDateTime.of(2026, 9, 10, 9, 0));
        interview.setUpdatedAt(LocalDateTime.of(2026, 9, 10, 9, 0));
        return interviewRepository.save(interview);
    }

    private List<String> subjects() {
        return mailSent.sent().stream().map(MailMessage::subject).toList();
    }

    private MailMessage onlyEmailWithSubjectContaining(String fragment) {
        List<MailMessage> matches = mailSent.sent().stream()
                .filter(m -> m.subject().contains(fragment))
                .toList();
        assertThat(matches).as("emails with a subject containing '%s'", fragment).hasSize(1);
        return matches.get(0);
    }

    private UserDetails acme() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }

    private UserDetails globex() {
        return userDetailsService.loadUserByUsername("talent@globex.local");
    }

    private UserDetails priya() {
        return userDetailsService.loadUserByUsername("priya@demo.local");
    }

    private UserDetails rohan() {
        return userDetailsService.loadUserByUsername("rohan@demo.local");
    }
}
