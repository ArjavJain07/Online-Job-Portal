package com.jobportal.support;

import com.jobportal.service.MailMessage;
import com.jobportal.service.MailService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

// Replaces MailService for every integration test (Section 12.1, hard requirement 5:
// "tests must not send real mail or need a network"). Imported by IntegrationTestBase
// alongside NoOpMailService, which is ALSO present there (app.mail.enabled is false in
// the test profile, same as everywhere else) - @Primary is what makes this the one
// actually injected, the exact "two beans of the same type, @Primary picks the
// differently-named one" trick FixedClockConfig already uses for Clock. That also means
// this class is a genuine extra layer of proof for hard requirement 2: the production
// no-op path is still fully wired up and present in the test context, just not the bean
// autowired into NotificationService.
//
// A test that needs to inspect what would have been emailed - capturing the token inside
// a password-reset link, for example - autowires RecordingMailService directly (not the
// MailService interface) and reads sent(). Cleared before every test by
// IntegrationTestBase's own @BeforeEach, since this is a singleton bean that would
// otherwise carry messages over from one test method to the next in the one Spring
// context Section 12.1 caches and shares across dozens of test classes.
@TestComponent
@Primary
public class RecordingMailService implements MailService {

    private final List<MailMessage> sent = new ArrayList<>();

    @Override
    public synchronized void send(MailMessage message) {
        sent.add(message);
    }

    public synchronized List<MailMessage> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }
}
