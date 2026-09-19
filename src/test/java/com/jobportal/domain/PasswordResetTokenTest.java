package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

// Unit test for PasswordResetToken.isUsable (Section 16 #1, hard requirement 4): the one
// place "is this link still good" is decided, the same "one definition" discipline
// Job.isLive gets (Section 3.5 design rule 4, JobTest).
class PasswordResetTokenTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 10, 0);

    private PasswordResetToken token(LocalDateTime expiresAt, LocalDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        return token;
    }

    @Test
    void unusedAndNotYetExpiredIsUsable() {
        assertThat(token(NOW.plusMinutes(1), null).isUsable(NOW)).isTrue();
    }

    @Test
    void expiryInThePastIsNotUsable() {
        assertThat(token(NOW.minusSeconds(1), null).isUsable(NOW)).isFalse();
    }

    // The boundary itself: a token expiring at exactly "now" has already expired, the same
    // "isAfter, not isBefore-or-equal" convention Job.isLive uses for a deadline of today
    // (JobTest#deadlineOfTodayIsStillLive is the mirror-image case - that one is inclusive
    // because a WHOLE DAY deadline means "any time today", but an expiry TIMESTAMP has no
    // such day-granularity excuse, so the instant itself is already too late).
    @Test
    void expiryOfExactlyNowIsNotUsable() {
        assertThat(token(NOW, null).isUsable(NOW)).isFalse();
    }

    @Test
    void usedTokenIsNotUsableEvenBeforeExpiry() {
        assertThat(token(NOW.plusMinutes(30), NOW.minusMinutes(1)).isUsable(NOW)).isFalse();
    }

    @Test
    void usedAndExpiredIsStillJustNotUsable() {
        assertThat(token(NOW.minusMinutes(1), NOW.minusMinutes(2)).isUsable(NOW)).isFalse();
    }
}
