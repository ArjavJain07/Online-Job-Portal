package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

// Unit tests for SmtpMailService, built by hand with a mocked JavaMailSender instead of a
// Spring context or a real SMTP server (Section 12.1, hard requirement 5). Two things
// matter here: the message is translated correctly, and - the point of this class,
// hard requirement 3 - a send failure never escapes send() as an exception.
class SmtpMailServiceTest {

    @Test
    void sendsThroughJavaMailSenderWithTheConfiguredFromAddress() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        SmtpMailService service = new SmtpMailService(javaMailSender, "noreply@jobportal.local");

        service.send(new MailMessage("priya@demo.local", "Subject line", "Body text"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("noreply@jobportal.local");
        assertThat(sent.getTo()).containsExactly("priya@demo.local");
        assertThat(sent.getSubject()).isEqualTo("Subject line");
        assertThat(sent.getText()).isEqualTo("Body text");
    }

    // Hard requirement 3, at its lowest level: an unreachable SMTP host must not surface as
    // an exception out of send() - by the time this runs it is always on the @Async
    // executor (NotificationService's class comment), so nothing downstream could catch
    // it, and the task is explicit that silently losing the failure without a trace is
    // also wrong (send() logs a warning on this exact path - see SmtpMailService).
    @Test
    void aFailedSendIsSwallowedNotThrown() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("Connection refused")).when(javaMailSender).send(any(SimpleMailMessage.class));
        SmtpMailService service = new SmtpMailService(javaMailSender, "noreply@jobportal.local");

        assertThatCode(() -> service.send(new MailMessage("priya@demo.local", "Subject", "Body")))
                .doesNotThrowAnyException();
    }
}
