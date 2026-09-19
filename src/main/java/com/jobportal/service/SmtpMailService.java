package com.jobportal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

// Real delivery through Spring Mail, active only once an operator has set
// app.mail.enabled=true (Section 16 #1). SMTP host/user/password never appear here or in
// any properties file with a real-looking value - see application-prod.properties, which
// binds them straight from environment variables the operator sets, exactly the way it
// already does for the database (hard requirement 1).
//
// This bean is only ever constructed once app.mail.enabled=true, and Spring's own
// JavaMailSender bean only exists once spring.mail.host is also set (Boot's
// MailSenderAutoConfiguration). So turning app.mail.enabled on without also configuring
// SMTP is a deployment mistake, and this class deliberately lets that mistake fail
// STARTUP with a clear "no bean of type JavaMailSender available" error - the same
// fail-fast philosophy already used for the upload folder (Section 7.4) and Flyway's
// validate (Section 10.7) - rather than silently falling back to doing nothing, which
// would hide a broken deploy behind a site that otherwise looks like it is working fine.
@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender javaMailSender;
    private final String fromAddress;

    public SmtpMailService(JavaMailSender javaMailSender, @Value("${app.mail.from}") String fromAddress) {
        this.javaMailSender = javaMailSender;
        this.fromAddress = fromAddress;
    }

    // Hard requirement 3: a failed send must neither block nor fail the user's action, and
    // must not vanish without a trace either. By the time this runs it is always on
    // AsyncConfig's executor (NotificationService is the only caller), so there is no HTTP
    // response left that COULD fail - the only choice left is "log it" or "silently drop
    // it", and the task is explicit that dropping it silently is the wrong one.
    // MailException is the common superclass for both an unreachable host
    // (MailSendException) and rejected credentials (MailAuthenticationException), so one
    // catch covers everything Spring Mail itself can throw from send().
    @Override
    public void send(MailMessage message) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(fromAddress);
            mail.setTo(message.to());
            mail.setSubject(message.subject());
            mail.setText(message.body());
            javaMailSender.send(mail);
        } catch (MailException e) {
            log.warn("Failed to send email to {} (subject '{}'): {}", message.to(), message.subject(), e.getMessage());
        }
    }
}
