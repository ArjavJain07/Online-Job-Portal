package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobportal.domain.SystemSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Unit tests for NotificationService's four email builders (Section 16 #1), built by hand
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
}
