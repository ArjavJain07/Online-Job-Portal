package com.jobportal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Hard requirement 2's "a logging no-op behind an interface": the default MailService,
// and the only one that ever runs on a developer machine, in the test suite
// (RecordingMailService overrides it there, src/test/.../support - Section 12.1) or on the
// server as it stands today, none of which have SMTP configured. Sending an email
// therefore costs one log line and nothing else - no exception, no delay, no half-sent
// state - so every caller (a job application being submitted, a status change, a job
// decision, a password-reset request) completes exactly as it would if this feature did
// not exist yet.
//
// The message body - which, for a password reset, contains the link and its single-use
// token - is logged only at DEBUG, never INFO. logging.level.com.jobportal=INFO is the
// default in every properties file this project ships (application.properties,
// application-prod.properties, application-test.properties), so a token only ever reaches
// a log if an operator has deliberately turned DEBUG logging on for this package on this
// specific run - never as a side effect of simply running the app with mail disabled. INFO
// gets only the recipient and subject, which is already enough to see, in the console,
// that the notification pipeline fired at all - useful for a demo or a viva with no real
// SMTP server to hand.
@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(NoOpMailService.class);

    @Override
    public void send(MailMessage message) {
        log.info("Email disabled (app.mail.enabled=false); would have sent '{}' to {}", message.subject(), message.to());
        log.debug("Suppressed email body:\n{}", message.body());
    }
}
