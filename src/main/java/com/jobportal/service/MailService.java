package com.jobportal.service;

// Sends one email (Section 16 #1, hard requirement 2). Exactly one of two implementations
// is ever active, chosen at startup by app.mail.enabled (application.properties):
// NoOpMailService (the default - logs only, so a machine with no SMTP configured, which
// today means every developer machine, the whole test suite and the live server, starts
// and works exactly as it did before this feature existed) or SmtpMailService (real
// delivery, once an operator sets app.mail.enabled=true together with the spring.mail.*
// properties). The one caller, NotificationService, injects this interface and never knows
// or cares which implementation answers it.
public interface MailService {

    void send(MailMessage message);
}
