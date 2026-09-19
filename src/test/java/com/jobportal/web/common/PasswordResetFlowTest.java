package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.PasswordResetToken;
import com.jobportal.domain.User;
import com.jobportal.repository.PasswordResetTokenRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.MailMessage;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.ResultActions;

// End-to-end tests for self-service password reset (Section 16 #1, I-17, hard requirement
// 4), built on RecordingMailService and SyncTaskExecutorConfig (both imported by
// IntegrationTestBase) instead of any real network or waiting (hard requirement 5).
//
// The half of this class that matters most is not "does a reset work" but "what does an
// anonymous caller learn" - the same emphasis LoginLockoutTest puts on its own class
// comment, and for the same reason: identicalResponseForUnknownAndKnownAddress and
// disabledAccountGetsTheSameConfirmation exist purely to pin that down. If either ever
// fails, this feature has turned into exactly the account-existence oracle Section 4.10
// went to trouble to deny.
class PasswordResetFlowTest extends IntegrationTestBase {

    private static final String KNOWN_EMAIL = "arjun@demo.local";
    private static final String OLD_PASSWORD = "Seeker@123";
    private static final String NEW_PASSWORD = "NewPass1word!";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private Clock clock;

    // THE enumeration guarantee. An unknown address and a real, enabled one must produce
    // byte-for-byte the same response - same redirect, same flash message - or a visitor
    // could tell the two apart with one submission.
    @Test
    void identicalResponseForUnknownAndKnownAddress() throws Exception {
        ResultActions unknown = submitForgotPassword("never-registered@demo.local");
        String unknownMessage = flashSuccessMessage(unknown);

        ResultActions known = submitForgotPassword(KNOWN_EMAIL);
        String knownMessage = flashSuccessMessage(known);

        assertThat(unknownMessage).isEqualTo(knownMessage);
        unknown.andExpect(redirectedUrl("/forgot-password"));
        known.andExpect(redirectedUrl("/forgot-password"));
    }

    // A deactivated account (Section 4.6) gets the very same confirmation as everyone else
    // - unlike /login's own accepted "?blocked" leak (Section 4.4), this page has no
    // equivalent reason to ever say otherwise (PasswordResetService's class comment) - and,
    // being unable to log in with any password, is never actually emailed a link.
    @Test
    void disabledAccountGetsTheSameConfirmationAndNoEmail() throws Exception {
        ResultActions result = submitForgotPassword("jobs@quickhire.local");

        result.andExpect(redirectedUrl("/forgot-password"));
        assertThat(flashSuccessMessage(result)).contains("If an account exists");
        assertThat(mailSent.sent()).isEmpty();
    }

    // The happy path: request, receive exactly one email with a working link, use it, and
    // the new password (not the old one) logs in afterwards.
    @Test
    void fullResetJourneyChangesThePasswordSuccessfully() throws Exception {
        submitForgotPassword(KNOWN_EMAIL);
        String token = extractSoleToken();

        mockMvc.perform(get("/reset-password").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose a new password")));

        submitResetPassword(token, NEW_PASSWORD, NEW_PASSWORD)
                .andExpect(redirectedUrl("/login?reset"));

        login(KNOWN_EMAIL, NEW_PASSWORD).andExpect(authenticated());
        login(KNOWN_EMAIL, OLD_PASSWORD).andExpect(redirectedUrl("/login?error"));
    }

    // Hard requirement 4's "single use": the link that just worked must not work again.
    @Test
    void aUsedTokenIsRejectedOnASecondAttempt() throws Exception {
        submitForgotPassword(KNOWN_EMAIL);
        String token = extractSoleToken();
        submitResetPassword(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));

        submitResetPassword(token, "AnotherPass2!", "AnotherPass2!")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
        mockMvc.perform(get("/reset-password").param("token", token))
                .andExpect(content().string(containsString("Link no longer valid")));
    }

    // Hard requirement 4's "expiry", exercised the same way LoginLockoutTest exercises the
    // lockout cooldown: the row is put into the state real elapsed time would leave
    // behind, using the fixed test Clock, rather than any test waiting for it.
    @Test
    void anExpiredTokenIsRejected() throws Exception {
        submitForgotPassword(KNOWN_EMAIL);
        String token = extractSoleToken();

        PasswordResetToken stored = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUser().getEmail().equals(KNOWN_EMAIL))
                .findFirst().orElseThrow();
        stored.setExpiresAt(LocalDateTime.now(clock).minusSeconds(1));
        passwordResetTokenRepository.save(stored);

        mockMvc.perform(get("/reset-password").param("token", token))
                .andExpect(content().string(containsString("Link no longer valid")));
    }

    // Only the newest emailed link is ever live: requesting a second time must retire the
    // first token, not merely add a second valid one.
    @Test
    void aNewRequestInvalidatesTheOlderToken() throws Exception {
        submitForgotPassword(KNOWN_EMAIL);
        String firstToken = extractSoleToken();

        submitForgotPassword(KNOWN_EMAIL);
        String secondToken = extractLatestToken();
        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(get("/reset-password").param("token", firstToken))
                .andExpect(content().string(containsString("Link no longer valid")));
        mockMvc.perform(get("/reset-password").param("token", secondToken))
                .andExpect(content().string(containsString("Choose a new password")));
    }

    // Hard requirement 4's last question, answered end to end: a locked-out account can
    // log in again immediately after a successful reset, with no need to wait out the
    // cooldown Section 4.10 would otherwise still be enforcing.
    @Test
    void successfulResetClearsAnActiveLockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(KNOWN_EMAIL, "WrongPassword1").andExpect(redirectedUrl("/login?error"));
        }
        User lockedUser = userRepository.findByEmail(KNOWN_EMAIL).orElseThrow();
        assertThat(lockedUser.getLockoutUntil()).isNotNull();

        submitForgotPassword(KNOWN_EMAIL);
        String token = extractSoleToken();
        submitResetPassword(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));

        User resetUser = userRepository.findByEmail(KNOWN_EMAIL).orElseThrow();
        assertThat(resetUser.getFailedLoginAttempts()).isZero();
        assertThat(resetUser.getLockoutUntil()).isNull();
        login(KNOWN_EMAIL, NEW_PASSWORD).andExpect(authenticated());
    }

    // Design rule 1 (Post/Redirect/Get): a validation failure re-renders the form instead
    // of redirecting, and does not spend the token - it is still usable afterwards.
    @Test
    void mismatchedConfirmationReRendersWithoutSpendingTheToken() throws Exception {
        submitForgotPassword(KNOWN_EMAIL);
        String token = extractSoleToken();

        submitResetPassword(token, NEW_PASSWORD, "SomethingElse1!")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passwords do not match")));

        submitResetPassword(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));
    }

    // A syntactically bad email is a normal field error, never a statement about the
    // database - and, unlike a well-formed address, it triggers no lookup or email at all.
    @Test
    void malformedEmailIsAnOrdinaryFieldErrorAndSendsNothing() throws Exception {
        mockMvc.perform(post("/forgot-password").param("email", "not-an-email").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please enter a valid email address")));
        assertThat(mailSent.sent()).isEmpty();
    }

    @Test
    void bothRoutesRequireCsrf() throws Exception {
        mockMvc.perform(post("/forgot-password").param("email", KNOWN_EMAIL))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reset-password").param("token", "x").param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isForbidden());
    }

    // A bare GET, with no token at all, must render the same safe "invalid" state rather
    // than a 400 - PasswordResetController's @RequestParam(required = false) is what this
    // pins down.
    @Test
    void resetPasswordWithNoTokenAtAllShowsInvalidState() throws Exception {
        mockMvc.perform(get("/reset-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
    }

    // /forgot-password is an entry point for an anonymous flow, exactly like /login and
    // /register (Section 6.1 P-3/P-4), so a logged-in visitor is bounced to their own
    // dashboard the same way. /reset-password is deliberately NOT gated the same way: its
    // real access control is the token in the query string, not the caller's own session -
    // completing a reset for the account that token names has no dependency on, and no
    // effect on, whoever else might happen to be logged in on the same browser.
    @Test
    void forgotPasswordRedirectsAnAlreadyLoggedInVisitorButResetPasswordDoesNotNeedTo() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(get("/forgot-password").with(user(priya))).andExpect(redirectedUrl("/dashboard"));
        mockMvc.perform(get("/reset-password").param("token", "whatever").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
    }

    // ---- helpers ----

    private ResultActions submitForgotPassword(String email) throws Exception {
        return mockMvc.perform(post("/forgot-password").param("email", email).with(csrf()));
    }

    private ResultActions submitResetPassword(String token, String newPassword, String confirmPassword) throws Exception {
        return mockMvc.perform(post("/reset-password")
                .param("token", token)
                .param("newPassword", newPassword)
                .param("confirmPassword", confirmPassword)
                .with(csrf()));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(new MockHttpSession())
                .with(csrf()));
    }

    private String flashSuccessMessage(ResultActions result) throws Exception {
        Object value = result.andReturn().getFlashMap().get("success");
        assertThat(value).isNotNull();
        return value.toString();
    }

    private String extractSoleToken() {
        assertThat(mailSent.sent()).hasSize(1);
        return tokenFrom(mailSent.sent().get(0));
    }

    private String extractLatestToken() {
        List<MailMessage> messages = mailSent.sent();
        assertThat(messages).isNotEmpty();
        return tokenFrom(messages.get(messages.size() - 1));
    }

    private String tokenFrom(MailMessage message) {
        Matcher matcher = TOKEN_PATTERN.matcher(message.body());
        assertThat(matcher.find()).as("email body should contain a reset link with a token").isTrue();
        return matcher.group(1);
    }
}
