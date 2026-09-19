package com.jobportal.service;

import com.jobportal.domain.Interview;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.InterviewStatus;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.repository.InterviewRepository;
import com.jobportal.web.form.InterviewCancelForm;
import com.jobportal.web.form.InterviewForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Scheduling, rescheduling and cancelling the interview attached to one job application
// (interview scheduling feature, built on the existing ApplicationStatus.INTERVIEW stage
// of Section 5.6). A service of its own rather than three more methods on
// JobApplicationService, which is already the largest class in the project and already
// carries four distinct concerns - the same split MessageService and PasswordResetService
// were given for the same reason (11.3 contract item 11).
//
// ===========================================================================
// THE ONE RULE THIS CLASS IS BUILT AROUND: it never moves an application's status
// ===========================================================================
// There is no path from any method here into ApplicationStatus. Scheduling requires the
// application to ALREADY be at INTERVIEW (requireInterviewStage below) and writes only the
// interviews table; the employer gets there through the existing "Change status" form,
// which is the single place a status may move (JobApplicationService.changeStatus ->
// recordStatusChange - the 14-of-49 matrix of Section 5.6, ApplicationStatusTest's 49
// parameterised cases, and the ApplicationStatusChange row that builds the candidate's
// dated timeline all hang off that one method). See Interview's class comment for why
// "scheduling implicitly moves SHORTLISTED -> INTERVIEW" was rejected rather than
// forgotten.
//
// The dependency direction is chosen to keep that true and to stay acyclic. This class
// depends on JobApplicationService (for the one ownership-checked read, getForEmployer);
// JobApplicationService does NOT depend on this class - where it has to cancel an
// interview automatically it goes to InterviewRepository directly. Wiring it the other way
// would put a service that CAN change status inside the one that must not be able to.
@Service
public class InterviewService {

    // Public so EmployerApplicationController and the tests can assert on the exact
    // wording, the same way JobService publishes DEADLINE_RANGE_MESSAGE and
    // JobApplicationService publishes ALREADY_APPLIED_MESSAGE.
    public static final String NOT_AT_INTERVIEW_STAGE_MESSAGE =
            "Move this candidate to the Interview stage before scheduling an interview.";
    public static final String PAST_MESSAGE = "An interview cannot be scheduled in the past.";
    public static final String TOO_FAR_AHEAD_MESSAGE = "An interview must be within the next 12 months.";
    public static final String NOTHING_SCHEDULED_MESSAGE = "There is no scheduled interview for this application.";
    public static final String ALREADY_SCHEDULED_MESSAGE =
            "This application already has a scheduled interview. Reschedule or cancel it instead.";

    // The far end of the sanity window (see validateSlot). Twelve months, chosen to be
    // obviously longer than any real hiring process rather than to be a rule anyone has to
    // plan around - its whole job is to catch a mistyped year.
    private static final int MAX_MONTHS_AHEAD = 12;

    // The reason written onto an interview that is cancelled automatically because its
    // application reached a final status (see JobApplicationService.recordStatusChange).
    // Lives here, next to the employer-initiated cancellation it has to read consistently
    // with, rather than in the calling method.
    public static final String CLOSED_APPLICATION_REASON =
            "The application was closed before the interview took place.";

    private final InterviewRepository interviewRepository;
    private final JobApplicationService jobApplicationService;
    private final NotificationService notificationService;
    private final Clock clock;

    public InterviewService(InterviewRepository interviewRepository, JobApplicationService jobApplicationService,
            NotificationService notificationService, Clock clock) {
        this.interviewRepository = interviewRepository;
        this.jobApplicationService = jobApplicationService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---- Reads ----

    // The interview attached to an application, whatever its state - a cancelled one is
    // returned too, because both the employer's page and the candidate's need to say "this
    // was arranged and then called off" rather than showing nothing at all.
    public Optional<Interview> findForApplication(Long applicationId) {
        return interviewRepository.findByApplication_Id(applicationId);
    }

    // One query for a whole page of applications (the candidate's tracking list, Section
    // 6.4 S-D2), keyed by application id so the template can look each row's interview up
    // by ${interviews.get(a.id)} - the same Map<Long, ...> shape that page already uses for
    // its unread message counts. An empty input short-circuits: `where application_id in
    // ()` is not valid SQL everywhere and Spring Data's rendering of an empty collection is
    // not worth relying on.
    public Map<Long, Interview> byApplicationId(Collection<Long> applicationIds) {
        Map<Long, Interview> byId = new LinkedHashMap<>();
        if (applicationIds == null || applicationIds.isEmpty()) {
            return byId;
        }
        for (Interview interview : interviewRepository.findByApplication_IdIn(applicationIds)) {
            byId.put(interview.getApplication().getId(), interview);
        }
        return byId;
    }

    // ---- Employer actions ----

    // Schedule the first interview for an application, or replace one that was previously
    // cancelled. The "previously cancelled" case revives the existing row rather than
    // inserting a second one, because uk_interview_application (V6) allows only one row per
    // application - and that is the right constraint, not an obstacle to work around: the
    // candidate's page has to be able to answer "what, if anything, is arranged?" with one
    // unambiguous answer. Scheduling after a cancellation is a NEW arrangement, so the
    // reschedule bookkeeping is cleared (count back to zero, no "moved from" time) and the
    // candidate gets the plain "an interview has been scheduled" email, not the "your
    // interview has moved" one - they have nothing in their calendar to correct.
    @Transactional
    public Interview schedule(Long applicationId, Long employerId, InterviewForm form) {
        JobApplication application = jobApplicationService.getForEmployer(applicationId, employerId);
        requireInterviewStage(application);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime scheduledAt = form.scheduledAt();
        validateSlot(scheduledAt, now);

        Interview interview = interviewRepository.findByApplication_Id(applicationId).orElseGet(Interview::new);
        if (interview.getId() != null && interview.getStatus() == InterviewStatus.SCHEDULED) {
            throw new BusinessRuleException(ALREADY_SCHEDULED_MESSAGE);
        }

        interview.setApplication(application);
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setScheduledAt(scheduledAt);
        // The zone the wall clock above belongs to, read from the injected Clock rather
        // than ZoneId.systemDefault() (Section 7.10 applies to zones exactly as it applies
        // to instants - a test with a fixed clock must get that clock's zone, not the
        // machine's). Written on every (re)schedule rather than only on insert, so a revived
        // row cannot keep a zone from before a server move while carrying a time from after
        // it.
        interview.setTimeZone(clock.getZone().getId());
        interview.setMode(form.getMode());
        interview.setLocation(trimToNull(form.getLocation()));
        interview.setNotes(trimToNull(form.getNotes()));
        interview.setPreviousScheduledAt(null);
        interview.setRescheduleCount(0);
        interview.setCancellationReason(null);
        interview.setCancelledAt(null);
        if (interview.getCreatedAt() == null) {
            interview.setCreatedAt(now);
        }
        interview.setUpdatedAt(now);
        interviewRepository.save(interview);

        // Last statement, with every value resolved here while this transaction is still
        // open - see NotificationService's class comment for why that is not a style
        // preference.
        notificationService.notifyInterviewScheduled(application.getSeeker().getEmail(),
                application.getSeeker().getFullName(), application.getJob().getTitle(), application.getReference(),
                interview.getWhenText(), interview.getMode().getLabel(), interview.getMode().getDetailLabel(),
                interview.getLocation(), interview.getNotes());
        return interview;
    }

    // Change a scheduled interview: a new slot, a different mode, a corrected link, or any
    // combination. Whether this counts as a RESCHEDULE is decided by the data, not by which
    // button was pressed - the slot is compared with the stored one, and only a genuine move
    // sets previousScheduledAt and bumps rescheduleCount. That matters because those two
    // fields are what the candidate's page uses to say "moved from ...", and a fix to a typo
    // in a joining link must not tell someone their appointment has changed time.
    //
    // A past interview can still be rescheduled (the slot came and went, and the employer is
    // arranging another one); validateSlot only ever judges the NEW time. What cannot be
    // rescheduled is a cancelled interview - that is schedule()'s job, and the two differ in
    // the email the candidate gets.
    @Transactional
    public Interview reschedule(Long applicationId, Long employerId, InterviewForm form) {
        JobApplication application = jobApplicationService.getForEmployer(applicationId, employerId);
        requireInterviewStage(application);
        Interview interview = requireScheduled(applicationId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime scheduledAt = form.scheduledAt();
        validateSlot(scheduledAt, now);

        boolean moved = !scheduledAt.equals(interview.getScheduledAt());
        if (moved) {
            interview.setPreviousScheduledAt(interview.getScheduledAt());
            interview.setRescheduleCount(interview.getRescheduleCount() + 1);
            interview.setScheduledAt(scheduledAt);
        }
        interview.setTimeZone(clock.getZone().getId());
        interview.setMode(form.getMode());
        interview.setLocation(trimToNull(form.getLocation()));
        interview.setNotes(trimToNull(form.getNotes()));
        interview.setUpdatedAt(now);
        interviewRepository.save(interview);

        notificationService.notifyInterviewRescheduled(application.getSeeker().getEmail(),
                application.getSeeker().getFullName(), application.getJob().getTitle(), application.getReference(),
                interview.getWhenText(), moved ? interview.getPreviousWhenText() : null,
                interview.getMode().getLabel(), interview.getMode().getDetailLabel(), interview.getLocation(),
                interview.getNotes());
        return interview;
    }

    // Call a scheduled interview off. The row is kept (status = CANCELLED) rather than
    // deleted so the candidate's page can still show that something was arranged and then
    // called off, with the employer's reason if they gave one - deleting it would leave a
    // candidate who had already blocked out the time with no explanation anywhere, and
    // would also quietly destroy the only record that the employer ever made the
    // commitment.
    //
    // Cancelling an interview whose time has already passed is allowed and does exactly
    // this. It is an unusual thing to do but not a wrong one - "that session did not
    // happen" is a real correction - and refusing it would leave a stale "Scheduled" badge
    // on both pages with no way to clear it.
    @Transactional
    public Interview cancel(Long applicationId, Long employerId, InterviewCancelForm form) {
        JobApplication application = jobApplicationService.getForEmployer(applicationId, employerId);
        requireInterviewStage(application);
        Interview interview = requireScheduled(applicationId);

        String reason = trimToNull(form.getReason());
        interview.cancel(LocalDateTime.now(clock), reason);
        interviewRepository.save(interview);

        notificationService.notifyInterviewCancelled(application.getSeeker().getEmail(),
                application.getSeeker().getFullName(), application.getJob().getTitle(), application.getReference(),
                interview.getWhenText(), reason);
        return interview;
    }

    // ---- Rules ----

    // Scheduling is ATTACHED to the Interview stage, never a way into it (see this class's
    // header and Interview's). A stale page or a hand-crafted POST is the only way to reach
    // this with the wrong status, so it is a BusinessRuleException - flashed and redirected
    // by GlobalExceptionHandler (Section 7.3) - rather than a field error: there is no field
    // the employer could correct.
    //
    // Required by ALL THREE actions, not only schedule(). Once an application has left the
    // Interview stage it is at a final status (Section 5.6 allows nothing else from
    // INTERVIEW) and there is no legitimate edit left to make to its interview: an upcoming
    // one was already cancelled automatically on the way out
    // (JobApplicationService.recordStatusChange), and a past one is history that a
    // rejection does not entitle anyone to rewrite. Without this check the employer's page
    // would still offer "Cancel" on a rejected candidate's finished interview, and taking
    // it would email them "your interview on <a date last week> has been cancelled" -
    // technically true, actively confusing, and about an application that closed days ago.
    private void requireInterviewStage(JobApplication application) {
        if (application.getStatus() != ApplicationStatus.INTERVIEW) {
            throw new BusinessRuleException(NOT_AT_INTERVIEW_STAGE_MESSAGE);
        }
    }

    private Interview requireScheduled(Long applicationId) {
        Interview interview = interviewRepository.findByApplication_Id(applicationId)
                .orElseThrow(() -> new BusinessRuleException(NOTHING_SCHEDULED_MESSAGE));
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BusinessRuleException(NOTHING_SCHEDULED_MESSAGE);
        }
        return interview;
    }

    // The two things that can be wrong with a slot the form itself cannot judge, both
    // measured against the injected Clock (Section 7.10) and both BusinessRuleExceptions
    // for the same reason JobService.validateDeadlineRange is one.
    //
    // PAST: catching this is the point. An interview scheduled for yesterday is always a
    // slip - a mistyped date, or a form filled in and submitted much later - and its cost
    // lands entirely on the candidate, who gets an email about an appointment that has
    // already gone. Note what this rule does NOT do: it judges the slot at the moment it is
    // WRITTEN and never again. A stored interview whose time has since passed is not
    // invalid, it is history, and it keeps rendering from the same fields with only the
    // wording around it changing (Interview.isPast) - nothing sweeps it, rewrites it or
    // hides it.
    //
    // TOO FAR AHEAD: the mirror-image slip. "2206" for "2026" passes the past check
    // perfectly well, and without an upper bound the candidate is emailed an interview
    // invitation for the twenty-third century and the employer has no idea anything went
    // wrong. Twelve months is far outside any real hiring process, so this only ever fires
    // on a typo.
    private void validateSlot(LocalDateTime scheduledAt, LocalDateTime now) {
        if (scheduledAt == null) {
            // Both halves are @NotNull on the form, so the controller re-renders with field
            // errors long before this - present only so a future caller that skips
            // validation fails loudly instead of writing a null into a not-null column.
            throw new BusinessRuleException("Please choose an interview date and time.");
        }
        if (!scheduledAt.isAfter(now)) {
            throw new BusinessRuleException(PAST_MESSAGE);
        }
        if (scheduledAt.isAfter(now.plusMonths(MAX_MONTHS_AHEAD))) {
            throw new BusinessRuleException(TOO_FAR_AHEAD_MESSAGE);
        }
    }

    // Blank-to-null so an untouched optional textarea is stored as null rather than "",
    // which is what lets every page and every email guard on `location != null` alone
    // instead of also testing for emptiness at each of the eight places they are read.
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
