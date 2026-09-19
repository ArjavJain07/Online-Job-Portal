package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobportal.domain.SystemSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Unit tests for NotificationService's email builders - the original four of Section 16 #1
// plus the three the interview scheduling feature added - built by hand
// with a mocked MailService and SettingsService instead of a Spring context (Section
// 12.1) - the same "no Spring context" shape FileStorageServiceTest and
// JobSweepSchedulerTest already use for a plain service. Calling an @Async method directly
// on a hand-built instance (not through a Spring proxy) runs it on the calling thread,
// which is exactly what these tests want: they are testing the CONTENT the method builds,
// not Spring's own, separately-trusted @Async machinery (that half is AsyncConfig/
// SyncTaskExecutorConfig's job, exercised end-to-end by the IntegrationTestBase tests).
class NotificationServiceTest {

    private static final String SITE_NAME = "Acme JobPortal";

    private MailService mailService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        SettingsService settingsService = mock(SettingsService.class);
        SystemSettings settings = new SystemSettings();
        settings.setSiteName(SITE_NAME);
        when(settingsService.get()).thenReturn(settings);

        notificationService = new NotificationService(mailService, settingsService);
    }

    private MailMessage captureSentMessage() {
        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void applicationReceivedGoesToTheEmployerWithSiteNameAndReference() {
        notificationService.notifyApplicationReceived("hr@acme.local", "Anita Rao", "Java Developer", "Priya Sharma",
                "APP-00016");

        MailMessage message = captureSentMessage();
        assertThat(message.to()).isEqualTo("hr@acme.local");
        assertThat(message.subject()).contains("Java Developer");
        assertThat(message.body()).contains("Anita Rao").contains("Priya Sharma").contains("Java Developer")
                .contains("APP-00016").contains(SITE_NAME);
    }

    @Test
    void applicationStatusChangedGoesToTheCandidateWithTheNewStatusLabel() {
        notificationService.notifyApplicationStatusChanged("priya@demo.local", "Priya Sharma", "Java Developer",
                "Shortlisted", "APP-00016");

        MailMessage message = captureSentMessage();
        assertThat(message.to()).isEqualTo("priya@demo.local");
        assertThat(message.body()).contains("Priya Sharma").contains("Java Developer").contains("Shortlisted")
                .contains("APP-00016").contains(SITE_NAME);
    }

    @Test
    void jobApprovedTellsTheEmployerItIsLive() {
        notificationService.notifyJobDecision("hr@acme.local", "Anita Rao", "Java Developer", true, null);

        MailMessage message = captureSentMessage();
        assertThat(message.subject()).containsIgnoringCase("approved");
        assertThat(message.body()).contains("Java Developer").contains(SITE_NAME);
        // An approval has no reason to show, whatever a stale caller might pass.
    }

    @Test
    void jobRejectedIncludesTheReasonWhenGiven() {
        notificationService.notifyJobDecision("hr@acme.local", "Anita Rao", "Java Developer", false,
                "Missing salary range");

        MailMessage message = captureSentMessage();
        assertThat(message.subject()).containsIgnoringCase("not approved");
        assertThat(message.body()).contains("Missing salary range");
    }

    @Test
    void jobRejectedWithoutAReasonOmitsTheReasonLine() {
        notificationService.notifyJobDecision("hr@acme.local", "Anita Rao", "Java Developer", false, null);

        MailMessage message = captureSentMessage();
        assertThat(message.body()).doesNotContain("Reason:");
    }

    @Test
    void passwordResetIncludesTheLinkAndExpiryAndNeverAnythingElse() {
        notificationService.notifyPasswordReset("priya@demo.local", "Priya Sharma",
                "https://example.com/reset-password?token=abc123", 60);

        MailMessage message = captureSentMessage();
        assertThat(message.to()).isEqualTo("priya@demo.local");
        assertThat(message.subject()).contains(SITE_NAME);
        assertThat(message.body()).contains("https://example.com/reset-password?token=abc123")
                .contains("60").contains("Priya Sharma");
    }

    // ==================== Interview scheduling ====================
    //
    // "whenText" arrives already finished, with its zone named - see the comment above
    // these three methods for why this class is handed the sentence rather than a
    // LocalDateTime. The tests pass a realistic one so that what they assert on is the
    // string a candidate would actually read.

    private static final String WHEN = "23 Sep 2026, 3:30 PM IST (Asia/Kolkata)";

    @Test
    void interviewScheduledGoesToTheCandidateWithTheTimeModeAndDetail() {
        notificationService.notifyInterviewScheduled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, "Video call", "Joining link", "https://meet.example.com/abc", "Bring a laptop.");

        MailMessage message = captureSentMessage();
        assertThat(message.to()).isEqualTo("priya@demo.local");
        assertThat(message.subject()).contains("Java Developer");
        assertThat(message.body()).contains("Priya Sharma").contains("APP-00016").contains(SITE_NAME)
                .contains("When: " + WHEN)
                .contains("How: Video call")
                // Labelled by the MODE's own word for the field, so the candidate is never
                // told to go to an "address" that is a URL.
                .contains("Joining link: https://meet.example.com/abc")
                .contains("Notes: Bring a laptop.");
    }

    // A phone interview may legitimately have no location and no notes - those lines are
    // then absent rather than present and empty, which is what stops an email reading
    // "Phone number: ".
    @Test
    void interviewScheduledOmitsTheDetailAndNotesLinesWhenThereAreNone() {
        notificationService.notifyInterviewScheduled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, "Phone", "Phone number", null, null);

        MailMessage message = captureSentMessage();
        assertThat(message.body()).contains("How: Phone").doesNotContain("Phone number:").doesNotContain("Notes:");
    }

    // A genuine move names the OLD time, because the candidate has a now-wrong entry in
    // their calendar to find and delete.
    @Test
    void interviewRescheduledNamesTheSlotItMovedFrom() {
        notificationService.notifyInterviewRescheduled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, "21 Sep 2026, 10:00 AM IST (Asia/Kolkata)", "Video call", "Joining link",
                "https://meet.example.com/abc", null);

        MailMessage message = captureSentMessage();
        assertThat(message.subject()).containsIgnoringCase("rescheduled");
        assertThat(message.body()).contains("moved from 21 Sep 2026, 10:00 AM IST (Asia/Kolkata)")
                .contains("When: " + WHEN);
    }

    // A null "moved from" means only the details changed, and the email must say so rather
    // than inventing a time change that did not happen.
    @Test
    void interviewDetailsOnlyUpdateSaysTheTimeHasNotChanged() {
        notificationService.notifyInterviewRescheduled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, null, "Video call", "Joining link", "https://meet.example.com/corrected", null);

        MailMessage message = captureSentMessage();
        assertThat(message.subject()).containsIgnoringCase("updated");
        assertThat(message.body()).contains("The time has not changed.").doesNotContain("moved from");
    }

    @Test
    void interviewCancelledNamesTheTimeItWasAndTheReasonWhenGiven() {
        notificationService.notifyInterviewCancelled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, "The panel is unavailable that week.");

        MailMessage message = captureSentMessage();
        assertThat(message.to()).isEqualTo("priya@demo.local");
        assertThat(message.subject()).containsIgnoringCase("cancelled");
        assertThat(message.body()).contains(WHEN).contains("Reason: The panel is unavailable that week.");
    }

    @Test
    void interviewCancelledWithoutAReasonOmitsTheReasonLine() {
        notificationService.notifyInterviewCancelled("priya@demo.local", "Priya Sharma", "Java Developer",
                "APP-00016", WHEN, null);

        MailMessage message = captureSentMessage();
        assertThat(message.body()).contains(WHEN).doesNotContain("Reason:");
    }
}
