package com.jobportal.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// Builds and sends every outbound email this application has (Section 16 #1): an
// employer's job receiving an application, a candidate's application status changing, a
// job being approved or rejected, a password-reset link, and - added with the interview
// scheduling feature - an interview being scheduled, rescheduled or cancelled. One place,
// so the site-name
// substitution (Section 7.5: SystemSettings.siteName, never hard-coded - see siteName()
// below) and the subject/body wording live in exactly one reviewable spot instead of being
// copied into every calling service. Email bodies are plain text and hand-written Java
// strings, not Thymeleaf templates: Section 16 already lists "editable email templates" as
// a deliberately out-of-scope admin setting, so there was no existing boundary to cross by
// keeping these simple.
//
// ===========================================================================
// Why every method below takes plain Strings and primitives, never an entity
// ===========================================================================
// Each method here is @Async. JobApplicationService, JobModerationService,
// PasswordResetService and InterviewService call one of them as the LAST thing they do
// inside their own
// @Transactional method, and because the target method lives on a DIFFERENT Spring bean,
// the call passes through the @Async proxy and returns immediately - the caller's
// transaction then commits exactly as fast as it would without this feature, which is
// what hard requirement 3 ("sending must never block or fail the user's action") actually
// asks for. The real work - building the message, possibly talking to an SMTP server -
// happens moments later, on AsyncConfig's small thread pool.
//
// That gap between "the caller returns" and "this method actually runs" is exactly why
// every parameter here is a plain, already-resolved String or primitive rather than a
// User/Job/JobApplication. Those entities are attached to the persistence context of the
// original request's transaction (open-in-view, Section 7.10), which may already be
// closed - committed or not - by the time the async thread wakes up; a lazy association
// touched from the wrong thread at that point throws LazyInitializationException, but a
// value that was simply read into a String beforehand cannot. So every caller resolves
// what it needs to say (names, a job title, a status label, a reference, a link) while its
// own transaction is still open, and only the finished words cross the thread boundary -
// this class never loads anything from a repository except the one, entity-free read in
// siteName() below, which is safe for the reason explained there.
//
// ===========================================================================
// Why @Async here, and not @TransactionalEventListener(phase = AFTER_COMMIT)
// ===========================================================================
// AFTER_COMMIT is the textbook way to defer a side effect until a transaction has actually
// landed, and was the first design tried here. It does not work for this project's test
// suite: IntegrationTestBase wraps every test method in one Spring-managed transaction
// that is always rolled back at the end, never committed (Section 12.1 - the same reason
// JobApplicationTest has to work around H2's identity counter surviving a rollback). An
// AFTER_COMMIT listener registers itself against that same test transaction and therefore
// never fires in any test built on IntegrationTestBase - not "runs late", never runs -
// which would leave this entire feature untestable through the project's normal MockMvc
// tests. Plain @Async (dispatched at the point of the call, not gated on commit) has a
// smaller, accepted gap instead: if something AFTER a notify call in a caller's method
// later throws and rolls the transaction back, an email could go out for a change that did
// not happen. Every call site places its notify*(...) call as the LAST statement precisely
// to keep that window as close to zero as it can be.
@Service
public class NotificationService {

    private final MailService mailService;
    private final SettingsService settingsService;

    public NotificationService(MailService mailService, SettingsService settingsService) {
        this.mailService = mailService;
        this.settingsService = settingsService;
    }

    // Trigger 1 of 3 (Section 16 #1): "an employer's job receives an application."
    // Called by JobApplicationService.apply().
    @Async
    public void notifyApplicationReceived(String employerEmail, String employerName, String jobTitle,
            String candidateName, String applicationReference) {
        String site = siteName();
        String subject = "New application for " + jobTitle;
        String body = "Hi " + employerName + ",\n\n"
                + candidateName + " has applied for \"" + jobTitle + "\" (" + applicationReference + ") on " + site
                + ".\n\n"
                + "Log in to " + site + " to review the application.\n\n"
                + "- " + site;
        mailService.send(new MailMessage(employerEmail, subject, body));
    }

    // Trigger 2 of 3: "a candidate's application status changes." Called only by
    // JobApplicationService.recordStatusChange() when the actor making the change is the
    // employer - never for the seeker's own withdrawal, which is the candidate's own
    // action and needs no email telling them about itself.
    @Async
    public void notifyApplicationStatusChanged(String candidateEmail, String candidateName, String jobTitle,
            String newStatusLabel, String applicationReference) {
        String site = siteName();
        String subject = "Update on your application for " + jobTitle;
        String body = "Hi " + candidateName + ",\n\n"
                + "Your application (" + applicationReference + ") for \"" + jobTitle + "\" on " + site
                + " is now: " + newStatusLabel + ".\n\n"
                + "Log in to " + site + " to see the full details.\n\n"
                + "- " + site;
        mailService.send(new MailMessage(candidateEmail, subject, body));
    }

    // Trigger 3 of 3: "a job is approved or rejected by an admin." Called by
    // JobModerationService.approve()/reject() - deliberately NOT by takeDown(), which the
    // task's list of triggers does not mention and which already has its own, separate
    // "your live job was taken down" context that a rejection email would misstate.
    @Async
    public void notifyJobDecision(String employerEmail, String employerName, String jobTitle, boolean approved,
            String rejectionReasonOrNull) {
        String site = siteName();
        String subject = approved ? "Your job posting was approved" : "Your job posting was not approved";
        StringBuilder body = new StringBuilder("Hi ").append(employerName).append(",\n\n");
        if (approved) {
            body.append("Good news - \"").append(jobTitle).append("\" is now live on ").append(site).append(".\n\n");
        } else {
            body.append("\"").append(jobTitle).append("\" was not approved on ").append(site).append(".\n\n");
            if (rejectionReasonOrNull != null && !rejectionReasonOrNull.isBlank()) {
                body.append("Reason: ").append(rejectionReasonOrNull).append("\n\n");
            }
        }
        body.append("Log in to ").append(site).append(" for details.\n\n- ").append(site);
        mailService.send(new MailMessage(employerEmail, subject, body.toString()));
    }

    // ==================== Interview scheduling ====================
    //
    // Three more triggers, one per thing that can happen to an appointment: it is made, it
    // changes, it is called off. They obey this class's one hard rule exactly as the four
    // above do - EVERY parameter is a String or primitive the caller resolved while its own
    // transaction was still open, never an Interview or a JobApplication - and for these
    // three that rule does one extra job worth naming. `whenText` is not a LocalDateTime
    // and not a formatting instruction: it is the finished sentence fragment
    // "23 Sep 2026, 3:30 PM IST (Asia/Kolkata)", produced by Interview#getWhenText, the one
    // definition every page renders from too. So the time in the candidate's inbox is
    // character-for-character the time on their application page, including the named zone
    // - passing a raw LocalDateTime instead would mean this class choosing a format and a
    // zone of its own on a background thread, which is precisely how the two come to
    // disagree and a candidate turns up an hour late.
    //
    // All three are addressed to the CANDIDATE only. The employer is the one taking the
    // action in every case, so there is nothing to tell them that they did not just type -
    // the same reasoning that already keeps notifyApplicationStatusChanged from emailing a
    // seeker about their own withdrawal.

    @Async
    public void notifyInterviewScheduled(String candidateEmail, String candidateName, String jobTitle,
            String applicationReference, String whenText, String modeLabel, String detailLabel,
            String locationOrNull, String notesOrNull) {
        String site = siteName();
        String subject = "Interview scheduled for " + jobTitle;
        StringBuilder body = new StringBuilder("Hi ").append(candidateName).append(",\n\n")
                .append("An interview has been scheduled for your application (").append(applicationReference)
                .append(") for \"").append(jobTitle).append("\" on ").append(site).append(".\n\n");
        appendDetails(body, whenText, modeLabel, detailLabel, locationOrNull, notesOrNull);
        body.append("\nLog in to ").append(site).append(" to see the full details.\n\n- ").append(site);
        mailService.send(new MailMessage(candidateEmail, subject, body.toString()));
    }

    // previousWhenTextOrNull is what separates the two things this one email covers: a
    // non-null value means the SLOT MOVED and the candidate has a now-wrong entry in their
    // calendar to find and fix, so the old time is named explicitly; null means only the
    // mode, link/address or notes changed and the time is untouched, where naming a "from"
    // time would invent a change that did not happen. One method rather than two because
    // the recipient's job is the same either way - re-read the details below - and the
    // caller already knows which case it is (InterviewService#reschedule compares the two
    // instants); this is the same nullable-argument shape notifyJobDecision already uses
    // for its rejection reason.
    @Async
    public void notifyInterviewRescheduled(String candidateEmail, String candidateName, String jobTitle,
            String applicationReference, String whenText, String previousWhenTextOrNull, String modeLabel,
            String detailLabel, String locationOrNull, String notesOrNull) {
        String site = siteName();
        boolean moved = previousWhenTextOrNull != null;
        String subject = (moved ? "Interview rescheduled for " : "Interview details updated for ") + jobTitle;
        StringBuilder body = new StringBuilder("Hi ").append(candidateName).append(",\n\n");
        if (moved) {
            body.append("Your interview for \"").append(jobTitle).append("\" (").append(applicationReference)
                    .append(") on ").append(site).append(" has been moved from ").append(previousWhenTextOrNull)
                    .append(".\n\nPlease update your calendar - the new details are below.\n\n");
        } else {
            body.append("The details of your interview for \"").append(jobTitle).append("\" (")
                    .append(applicationReference).append(") on ").append(site)
                    .append(" have been updated. The time has not changed.\n\n");
        }
        appendDetails(body, whenText, modeLabel, detailLabel, locationOrNull, notesOrNull);
        body.append("\nLog in to ").append(site).append(" to see the full details.\n\n- ").append(site);
        mailService.send(new MailMessage(candidateEmail, subject, body.toString()));
    }

    // Sent for BOTH ways an interview is cancelled (see InterviewStatus): the employer
    // calling it off themselves, and the automatic cancellation of a still-future interview
    // when the application leaves the Interview stage for a final status. The second of
    // those means a candidate can receive this alongside the status-change email from the
    // same action, which is intended, not duplication: "you were not selected" and "the
    // interview you have in your calendar for Thursday is off" are two different facts, and
    // a candidate who reads only the first still needs the second.
    @Async
    public void notifyInterviewCancelled(String candidateEmail, String candidateName, String jobTitle,
            String applicationReference, String whenText, String reasonOrNull) {
        String site = siteName();
        String subject = "Interview cancelled for " + jobTitle;
        StringBuilder body = new StringBuilder("Hi ").append(candidateName).append(",\n\n")
                .append("The interview scheduled for ").append(whenText).append(", for your application (")
                .append(applicationReference).append(") for \"").append(jobTitle).append("\" on ").append(site)
                .append(", has been cancelled.\n\n");
        if (reasonOrNull != null && !reasonOrNull.isBlank()) {
            body.append("Reason: ").append(reasonOrNull).append("\n\n");
        }
        body.append("Log in to ").append(site).append(" to see the full details.\n\n- ").append(site);
        mailService.send(new MailMessage(candidateEmail, subject, body.toString()));
    }

    // The "when / how / where / anything else" block the schedule and reschedule emails
    // both end with, written once so the two cannot drift apart. detailLabel is
    // InterviewMode's own word for what the location field means for THIS mode ("Joining
    // link" for a video call, "Address" on-site), so the email labels the value the same
    // way the page the candidate is being sent to does. Blank lines rather than any markup:
    // MailMessage bodies are plain text (see this class's header).
    private void appendDetails(StringBuilder body, String whenText, String modeLabel, String detailLabel,
            String locationOrNull, String notesOrNull) {
        body.append("When: ").append(whenText).append("\n");
        body.append("How: ").append(modeLabel).append("\n");
        if (locationOrNull != null && !locationOrNull.isBlank()) {
            body.append(detailLabel).append(": ").append(locationOrNull).append("\n");
        }
        if (notesOrNull != null && !notesOrNull.isBlank()) {
            body.append("Notes: ").append(notesOrNull).append("\n");
        }
    }

    // Password reset (hard requirement 4). resetLink already contains the raw, single-use
    // token (built by PasswordResetService); this method never sees anything but the
    // finished link, and never itself logs it - whether it is ever written anywhere is
    // entirely governed by MailService's own two implementations (see NoOpMailService's
    // class comment on why that logging is DEBUG-only).
    @Async
    public void notifyPasswordReset(String toEmail, String recipientName, String resetLink, int expiryMinutes) {
        String site = siteName();
        String subject = "Reset your " + site + " password";
        String body = "Hi " + recipientName + ",\n\n"
                + "We received a request to reset your " + site + " password. This link is valid for "
                + expiryMinutes + " minutes and can only be used once:\n\n" + resetLink + "\n\n"
                + "If you did not request this, you can safely ignore this email - your password will not change.\n\n"
                + "- " + site;
        mailService.send(new MailMessage(toEmail, subject, body));
    }

    // The one repository-backed read in this class, safe to make from the @Async thread
    // with no HTTP request active: Spring Data's SimpleJpaRepository opens its own short
    // read transaction per call, so systemSettingsRepository.findById(1) (inside
    // SettingsService.get()) works the same way here as it would from any other caller -
    // and Section 7.5 already forbids caching this value in memory, so re-reading it here
    // is not a shortcut this class is taking, it is the only correct way to read it.
    private String siteName() {
        return settingsService.get().getSiteName();
    }
}
