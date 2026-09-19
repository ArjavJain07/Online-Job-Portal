package com.jobportal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobportal.config.LockoutProperties;
import com.jobportal.domain.User;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Unit tests for the lockout arithmetic of Section 4.10, built by hand with a mocked
// UserRepository instead of a Spring context (Section 12.1), for one reason: a plain
// constructor lets each test choose its own Clock.
//
// That is how the cooldown is tested without a single sleep. The service is built twice -
// once at NOW, once at a second Clock.fixed sixteen minutes later - so a test can lock an
// account "now" and then ask the later service whether the lock has lifted. It is the
// same fixed-Clock discipline FixedClockConfig applies to the integration tests (7.10),
// with the freedom to stand at two instants in one test.
class LoginAttemptServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-09-16T04:30:00Z");
    private static final String EMAIL = "priya@demo.local";

    private static final LockoutProperties LOCKOUT = new LockoutProperties(5, 15);

    private UserRepository userRepository;
    private LoginAttemptService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = serviceAt(NOW);
        user = new User();
        user.setEmail(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    void countsConsecutiveFailures() {
        service.recordFailure(EMAIL);
        service.recordFailure(EMAIL);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(user.getLockoutUntil()).isNull();
    }

    @Test
    void locksForTheCooldownOnTheFinalAttempt() {
        failUpToTheThreshold();

        assertThat(user.getFailedLoginAttempts()).isEqualTo(LOCKOUT.maxAttempts());
        assertThat(user.getLockoutUntil())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZONE).plusMinutes(LOCKOUT.cooldownMinutes()));
    }

    // An active lock is never pushed further out, so a bot cannot hold an account shut
    // indefinitely by carrying on guessing.
    @Test
    void furtherFailuresDuringTheCooldownChangeNothing() {
        failUpToTheThreshold();
        LocalDateTime unlockAt = user.getLockoutUntil();

        service.recordFailure(EMAIL);
        service.recordFailure(EMAIL);

        assertThat(user.getLockoutUntil()).isEqualTo(unlockAt);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(LOCKOUT.maxAttempts());
    }

    // Time travel, not sleeping: the lock is set by a service standing at NOW and read by
    // a second service standing sixteen minutes later.
    @Test
    void lockHasLiftedOnceTheCooldownHasPassed() {
        failUpToTheThreshold();
        assertThat(service.activeLock(user)).isPresent();

        LoginAttemptService afterCooldown = serviceAt(NOW.plusSeconds((LOCKOUT.cooldownMinutes() + 1) * 60L));

        assertThat(afterCooldown.activeLock(user)).isEmpty();
    }

    // One second before the cooldown ends the account is still locked - the boundary is
    // "strictly after now", not "roughly now".
    @Test
    void lockIsStillActiveOneSecondBeforeItExpires() {
        failUpToTheThreshold();

        LoginAttemptService justBefore = serviceAt(NOW.plusSeconds(LOCKOUT.cooldownMinutes() * 60L - 1));

        assertThat(justBefore.activeLock(user)).contains(user.getLockoutUntil());
    }

    // After the cooldown the user gets a full fresh set of attempts, instead of the next
    // single mistake re-locking them immediately.
    @Test
    void failureAfterTheCooldownStartsCountingAgainFromOne() {
        failUpToTheThreshold();

        LoginAttemptService afterCooldown = serviceAt(NOW.plusSeconds((LOCKOUT.cooldownMinutes() + 1) * 60L));
        afterCooldown.recordFailure(EMAIL);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockoutUntil()).isNull();
    }

    @Test
    void successClearsBothCounterAndLock() {
        failUpToTheThreshold();

        service.clearFailuresOn(user);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockoutUntil()).isNull();
    }

    // An address with no account writes nothing at all. Anything persisted per attempted
    // email would be a place to look up whether that address is registered.
    @Test
    void unknownAddressIsNotRecorded() {
        when(userRepository.findByEmail("nobody@demo.local")).thenReturn(Optional.empty());

        service.recordFailure("nobody@demo.local");

        verify(userRepository, never()).save(any());
    }

    // The typed email is normalised the same way AppUserDetailsService normalises it
    // (4.2/4.3); otherwise "Priya@Demo.local" would quietly get a counter of its own and
    // five guesses per capitalisation.
    @Test
    void emailIsNormalisedBeforeLookup() {
        service.recordFailure("  PRIYA@Demo.Local  ");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void nullEmailIsHarmless() {
        when(userRepository.findByEmail("")).thenReturn(Optional.empty());

        service.recordFailure(null);

        verify(userRepository, never()).save(any());
    }

    private LoginAttemptService serviceAt(Instant instant) {
        return new LoginAttemptService(userRepository, Clock.fixed(instant, ZONE), LOCKOUT);
    }

    private void failUpToTheThreshold() {
        for (int attempt = 0; attempt < LOCKOUT.maxAttempts(); attempt++) {
            service.recordFailure(EMAIL);
        }
    }
}
