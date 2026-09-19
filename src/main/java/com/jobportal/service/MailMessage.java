package com.jobportal.service;

// One outgoing email, fully rendered (Section 16 #1). Deliberately just three plain
// Strings, with nothing about HOW it gets sent: NotificationService builds one of these
// from the actual JPA entities while the calling service's transaction is still open, and
// hands it to MailService.send from a different, @Async thread. Carrying only immutable
// Strings across that thread boundary - never a User, Job or JobApplication - means the
// async side never touches Hibernate's persistence context, so there is nothing to go
// stale or throw LazyInitializationException no matter how the original request's
// transaction has since ended (see NotificationService's class comment for the full
// reasoning).
public record MailMessage(String to, String subject, String body) {
}
