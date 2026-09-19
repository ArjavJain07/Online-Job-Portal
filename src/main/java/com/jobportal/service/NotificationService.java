package com.jobportal.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// Builds and sends every outbound email this application has (Section 16 #1): an
// employer's job receiving an application, a candidate's application status changing, a
// job being approved or rejected, and a password-reset link. One place, so the site-name
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
// Each method here is @Async. JobApplicationService, JobModerationService and
// PasswordResetService call one of them as the LAST thing they do inside their own
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
