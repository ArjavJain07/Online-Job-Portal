package com.jobportal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.config.LockoutProperties;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

// Brute-force protection on the login form (Section 4.10): consecutive failures per
// account, a temporary lockout with a cooldown, a reset on success, and the /login?locked
// message.
//
// The half of this class that matters most is not "does it lock" but "what does an
// anonymous caller learn". Two tests exist purely to pin that down:
// lockedAccountAnswersAWrongPasswordLikeAnUnknownAddress and
// craftedLockedUrlCannotStateAnUnlockTime. If either ever fails, the lockout has turned
// into an account-existence oracle and the feature is worse than not having it.
//
// No test sleeps. The cooldown is exercised through the fixed Clock of FixedClockConfig
// (Section 7.10 / 12.1): "the cooldown has passed" is written into the row as a
// lockoutUntil before LocalDateTime.now(clock), which is exactly the state real elapsed
// time would leave behind. LoginAttemptServiceTest does the same with two fixed clocks at
// different instants, so the arithmetic itself is covered by moving time, not by waiting.
class LoginLockoutTest extends IntegrationTestBase {

    private static final String EMAIL = "arjun@demo.local";
    private static final String PASSWORD = "Seeker@123";
    private static final String WRONG_PASSWORD = "WrongPassword1";
    private static final String DEACTIVATED_EMAIL = "jobs@quickhire.local";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private LockoutProperties lockout;
    @Autowired
    private Clock clock;

    // The threshold itself: maxAttempts consecutive wrong passwords set the cooldown, and
    // every one of those answers is the same generic /login?error - the user is never
    // counted down ("2 attempts left"), because that count is only meaningful to someone
    // who does not own the account.
    @Test
    void consecutiveFailuresLockTheAccount() throws Exception {
        for (int attempt = 1; attempt <= lockout.maxAttempts(); attempt++) {
            login(EMAIL, WRONG_PASSWORD, new MockHttpSession())
                    .andExpect(redirectedUrl("/login?error"));
        }

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getFailedLoginAttempts()).isEqualTo(lockout.maxAttempts());
        assertThat(arjun.getLockoutUntil())
                .isEqualTo(LocalDateTime.now(clock).plusMinutes(lockout.cooldownMinutes()));
    }

    // One short of the threshold is not a lockout.
    @Test
    void failuresBelowTheThresholdDoNotLock() throws Exception {
        for (int attempt = 1; attempt < lockout.maxAttempts(); attempt++) {
            login(EMAIL, WRONG_PASSWORD, new MockHttpSession())
                    .andExpect(redirectedUrl("/login?error"));
        }

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getFailedLoginAttempts()).isEqualTo(lockout.maxAttempts() - 1);
        assertThat(arjun.getLockoutUntil()).isNull();
    }

    // THE enumeration guarantee (Section 4.10). Once the account is locked, a wrong
    // password against it must be answered exactly like a wrong password against an
    // address that has never existed. If the locked account answered differently, five
    // junk requests would tell an attacker whether any given email is registered - a far
    // wider leak than the deactivated-account trade-off 4.4 accepted.
    @Test
    void lockedAccountAnswersAWrongPasswordLikeAnUnknownAddress() throws Exception {
        lockAccount();

        login(EMAIL, WRONG_PASSWORD, new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
        login("never-registered@demo.local", WRONG_PASSWORD, new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    // Hammering a locked account must not push the cooldown further out. An extendable
    // lockout is a denial-of-service switch: anyone who knows an address could keep its
    // owner locked out for good just by posting the form in a loop.
    @Test
    void furtherFailuresDoNotExtendAnActiveLock() throws Exception {
        lockAccount();
        LocalDateTime unlockAt = userRepository.findByEmail(EMAIL).orElseThrow().getLockoutUntil();

        for (int attempt = 0; attempt < 10; attempt++) {
            login(EMAIL, WRONG_PASSWORD, new MockHttpSession())
                    .andExpect(redirectedUrl("/login?error"));
        }

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getLockoutUntil()).isEqualTo(unlockAt);
        assertThat(arjun.getFailedLoginAttempts()).isEqualTo(lockout.maxAttempts());
    }

    // The right password during a cooldown is refused - no session is granted - and this
    // is the one caller told about the lock, because it has just proved it knows the
    // password and so already knows the account exists.
    @Test
    void lockedAccountRefusesTheRightPasswordAndSaysWhenItUnlocks() throws Exception {
        lockAccount();
        MockHttpSession session = new MockHttpSession();

        login(EMAIL, PASSWORD, session)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?locked"))
                .andExpect(unauthenticated());

        mockMvc.perform(get("/login").param("locked", "").session(session))
                .andExpect(content().string(containsString(
                        "Too many failed login attempts, so this account is temporarily locked.")))
                .andExpect(content().string(containsString("You can try again in about 15 minutes.")));

        // Refused really means refused: the session never became a logged-in one.
        mockMvc.perform(get("/seeker/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // The unlock time is read once out of the session, so a reload does not keep
    // repeating a countdown that is drifting out of date, and a ?locked URL pasted to
    // somebody else states no time at all. Together with the model attribute never coming
    // from ${param.*}, this is what stops a crafted link putting words in the page.
    @Test
    void craftedLockedUrlCannotStateAnUnlockTime() throws Exception {
        mockMvc.perform(get("/login").param("locked", "").param("lockedForMinutes", "240")
                        .session(new MockHttpSession()))
                .andExpect(content().string(containsString("Please try again in a few minutes.")))
                .andExpect(content().string(not(containsString("240"))));
    }

    // Reading the message off a redirect does not leave it lying around for the next
    // page view.
    @Test
    void lockedMessageIsShownOnlyOnce() throws Exception {
        lockAccount();
        MockHttpSession session = new MockHttpSession();
        login(EMAIL, PASSWORD, session).andExpect(redirectedUrl("/login?locked"));

        mockMvc.perform(get("/login").param("locked", "").session(session))
                .andExpect(content().string(containsString("You can try again in about 15 minutes.")));
        mockMvc.perform(get("/login").param("locked", "").session(session))
                .andExpect(content().string(containsString("Please try again in a few minutes.")));
    }

    // "Consecutive" failures: getting in wipes the count, so a user who mistypes twice a
    // week never creeps up on the threshold.
    @Test
    void successfulLoginClearsTheCounter() throws Exception {
        login(EMAIL, WRONG_PASSWORD, new MockHttpSession()).andExpect(redirectedUrl("/login?error"));
        login(EMAIL, WRONG_PASSWORD, new MockHttpSession()).andExpect(redirectedUrl("/login?error"));
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isEqualTo(2);

        login(EMAIL, PASSWORD, new MockHttpSession())
                .andExpect(redirectedUrl("/seeker/dashboard"))
                .andExpect(authenticated());

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getFailedLoginAttempts()).isZero();
        assertThat(arjun.getLockoutUntil()).isNull();
    }

    // Cooldown served. A lockoutUntil in the past is exactly the row real elapsed time
    // would leave behind, so the login goes through again and the state is cleared.
    @Test
    void lockLiftsOnceTheCooldownHasPassed() throws Exception {
        lockAccount();
        expireTheLock();

        login(EMAIL, PASSWORD, new MockHttpSession())
                .andExpect(redirectedUrl("/seeker/dashboard"))
                .andExpect(authenticated());

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getFailedLoginAttempts()).isZero();
        assertThat(arjun.getLockoutUntil()).isNull();
    }

    // ...and the user gets a whole fresh set of attempts afterwards, rather than being
    // re-locked by the first mistake they make once the cooldown ends.
    @Test
    void firstFailureAfterTheCooldownStartsAFreshCount() throws Exception {
        lockAccount();
        expireTheLock();

        login(EMAIL, WRONG_PASSWORD, new MockHttpSession()).andExpect(redirectedUrl("/login?error"));

        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(arjun.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(arjun.getLockoutUntil()).isNull();
    }

    // Regression on 4.4: a deactivated account is refused by Spring before the password is
    // compared, so those failures are not wrong passwords and are not counted. Counting
    // them would let anyone "lock" an account that is already barred, and would change the
    // documented ?blocked behaviour.
    @Test
    void deactivatedAccountIsNeverCounted() throws Exception {
        for (int attempt = 0; attempt <= lockout.maxAttempts(); attempt++) {
            login(DEACTIVATED_EMAIL, WRONG_PASSWORD, new MockHttpSession())
                    .andExpect(redirectedUrl("/login?blocked"));
        }

        User suresh = userRepository.findByEmail(DEACTIVATED_EMAIL).orElseThrow();
        assertThat(suresh.getFailedLoginAttempts()).isZero();
        assertThat(suresh.getLockoutUntil()).isNull();
    }

    // An address with no account has nothing to count against. Nothing is created for it -
    // a per-email record that outlived the request would itself be somewhere to look up
    // whether an address is known.
    @Test
    void unknownAddressCreatesNoAccountState() throws Exception {
        long usersBefore = userRepository.count();

        for (int attempt = 0; attempt <= lockout.maxAttempts(); attempt++) {
            login("never-registered@demo.local", WRONG_PASSWORD, new MockHttpSession())
                    .andExpect(redirectedUrl("/login?error"));
        }

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // Every refused attempt still reaches the admin activity feed (4.4), including the
    // one refused by the lockout, and exactly one row per attempt.
    @Test
    void everyRefusedAttemptIsLoggedOnce() throws Exception {
        long before = countLoginFailures();
        lockAccount();
        login(EMAIL, PASSWORD, new MockHttpSession()).andExpect(redirectedUrl("/login?locked"));

        assertThat(countLoginFailures()).isEqualTo(before + lockout.maxAttempts() + 1);
    }

    private void lockAccount() throws Exception {
        for (int attempt = 0; attempt < lockout.maxAttempts(); attempt++) {
            login(EMAIL, WRONG_PASSWORD, new MockHttpSession());
        }
    }

    // Puts the row into the state the cooldown elapsing would produce, without any test
    // waiting for it.
    private void expireTheLock() {
        User arjun = userRepository.findByEmail(EMAIL).orElseThrow();
        arjun.setLockoutUntil(LocalDateTime.now(clock).minusSeconds(1));
        userRepository.save(arjun);
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }

    private long countLoginFailures() {
        return activityLogRepository.findAll().stream()
                .filter(log -> log.getType() == ActivityType.LOGIN_FAILED)
                .count();
    }
}
