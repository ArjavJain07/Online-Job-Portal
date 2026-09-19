package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

// Unit test for NoOpMailService (Section 16 #1, hard requirement 2): the default
// MailService on every machine with no SMTP configured. There is not much logic to test -
// that is the point of a no-op - so this only pins down the one thing that actually
// matters: sending never throws, whatever the message contains.
class NoOpMailServiceTest {

    @Test
    void sendNeverThrows() {
        NoOpMailService service = new NoOpMailService();

        assertThatCode(() -> service.send(new MailMessage("priya@demo.local", "Subject", "Body with a link and a token")))
                .doesNotThrowAnyException();
    }
}
