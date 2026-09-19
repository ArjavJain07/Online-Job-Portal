package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobportal.config.PasswordResetProperties;
import com.jobportal.domain.PasswordResetToken;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.repository.PasswordResetTokenRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.security.LoginAttemptService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Unit tests for the parts of PasswordResetService that are pure logic over mocked
// collaborators, built by hand instead of a Spring context (Section 12.1) - the same shape
// LoginAttemptServiceTest already uses for the login lockout this feature deliberately
// must not weaken. The stateful, round-trip half (a token surviving a real save/find/
// delete cycle, single-use actually enforced by the database, the full HTTP flow and the
// enumeration guarantee) is PasswordResetFlowTest instead: faking that behaviour by hand
// here would mean half-reimplementing a repository, which is more likely to hide a bug
// than catch one.
class PasswordResetServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW_INSTANT = Instant.parse("2026-09-16T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final PasswordResetProperties PROPERTIES = new PasswordResetProperties(60);
    private static final String RESET_URL_BASE = "https://jobportal.example/reset-password";

    private UserRepository userRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private NotificationService notificationService;
    private ActivityLogService activityLogService;
    private LoginAttemptService loginAttemptService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        notificationService = mock(NotificationService.class);
        activityLogService = mock(ActivityLogService.class);
        loginAttemptService = mock(LoginAttemptService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}hashed");

        service = new PasswordResetService(userRepository, passwordResetTokenRepository, passwordEncoder,
                loginAttemptService, activityLogService, notificationService, PROPERTIES, CLOCK);
    }

    private User seeker(boolean enabled) {
        User user = new User();
        user.setId(42L);
        user.setEmail("priya@demo.local");
        user.setFullName("Priya Sharma");
        user.setEnabled(enabled);
        return user;
    }

    // Hard requirement 4's enumeration guarantee, at the unit level: an unknown address
    // does no database writes and sends no email - see PasswordResetFlowTest for the
    // HTTP-visible half of this same guarantee (identical response either way).
    @Test
    void unknownEmailDoesNothing() {
        when(userRepository.findByEmail("nobody@demo.local")).thenReturn(Optional.empty());

        service.requestReset("nobody@demo.local", RESET_URL_BASE);

        verifyNoInteractions(passwordResetTokenRepository, notificationService);
    }

    // See PasswordResetService's class comment for why a disabled account is folded into
    // exactly the same "do nothing" branch as an unknown one, unlike /login.
    @Test
    void disabledAccountDoesNothingEither() {
        when(userRepository.findByEmail("priya@demo.local")).thenReturn(Optional.of(seeker(false)));

        service.requestReset("priya@demo.local", RESET_URL_BASE);

        verifyNoInteractions(passwordResetTokenRepository, notificationService);
    }

    @Test
    void emailIsNormalisedBeforeLookup() {
        when(userRepository.findByEmail("priya@demo.local")).thenReturn(Optional.empty());

        service.requestReset("  Priya@Demo.Local  ", RESET_URL_BASE);

        verify(userRepository).findByEmail("priya@demo.local");
    }

    // Hard requirement 4's "token entropy and storage": the raw token that goes out in the
    // email link must never equal, or trivially relate to, what gets written to the
    // database - it is a SHA-256 hex digest instead (64 lower-case hex characters), and
    // that digest genuinely is the hash of the token that was emailed (round-tripped
    // through MessageDigest here the same way PasswordResetService itself computes it, so
    // this test would fail if the algorithm or encoding ever silently changed).
    @Test
    void storesOnlyAHashOfTheTokenNeverTheRawValue() throws Exception {
        when(userRepository.findByEmail("priya@demo.local")).thenReturn(Optional.of(seeker(true)));

        service.requestReset("priya@demo.local", RESET_URL_BASE);

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        String storedHash = tokenCaptor.getValue().getTokenHash();

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyPasswordReset(eq("priya@demo.local"), eq("Priya Sharma"), linkCaptor.capture(),
                eq(60));
        String emailedLink = linkCaptor.getValue();
        String rawToken = emailedLink.substring((RESET_URL_BASE + "?token=").length());

        assertThat(storedHash).matches(Pattern.compile("^[0-9a-f]{64}$"));
        assertThat(storedHash).isNotEqualTo(rawToken);
        assertThat(sha256Hex(rawToken)).isEqualTo(storedHash);
        // 32 random bytes, base64url-without-padding: 256 bits of entropy (hard
        // requirement 4), 43 characters, and already exactly the URL query-parameter
        // character set - no percent-encoding needed anywhere this token travels.
        assertThat(rawToken).hasSize(43).matches("^[A-Za-z0-9_-]+$");
    }

    private String sha256Hex(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // Hard requirement 4's "expiry", tied to the fixed Clock the same way LoginAttemptServiceTest
    // ties the lockout cooldown to it: no test may sleep, so the expiry instant is read
    // straight back off the saved entity instead of waited for.
    @Test
    void expiryIsNowPlusTheConfiguredMinutes() {
        when(userRepository.findByEmail("priya@demo.local")).thenReturn(Optional.of(seeker(true)));

        service.requestReset("priya@demo.local", RESET_URL_BASE);

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(NOW.plusMinutes(60));
    }

    // Every request deletes any older token for the account BEFORE inserting the new one -
    // asserted here by call order, since Mockito's InOrder is what actually proves
    // "before", not just "both happened".
    @Test
    void anOlderTokenIsDeletedBeforeTheNewOneIsInserted() {
        when(userRepository.findByEmail("priya@demo.local")).thenReturn(Optional.of(seeker(true)));

        service.requestReset("priya@demo.local", RESET_URL_BASE);

        InOrder order = inOrder(passwordResetTokenRepository);
        order.verify(passwordResetTokenRepository).deleteByUser_Id(42L);
        order.verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    // isValidToken() is a read-only preview (GET /reset-password): it must never look
    // anything up, let alone mark a token used, for an empty or missing value - the
    // controller calls this for a bare GET /reset-password with no query string at all.
    @Test
    void isValidTokenRejectsBlankOrNullWithoutTouchingTheRepository() {
        assertThat(service.isValidToken(null)).isFalse();
        assertThat(service.isValidToken("")).isFalse();
        assertThat(service.isValidToken("   ")).isFalse();
        verifyNoInteractions(passwordResetTokenRepository);
    }

    @Test
    void isValidTokenIsFalseWhenNoTokenMatchesTheHash() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.isValidToken("some-token-nobody-issued")).isFalse();
    }

    // A stray BusinessRuleException must never reach a caller who already checked
    // isValidToken() and got true - resetPassword's own re-check exists for the RACE
    // between those two calls, not to second-guess a token this mock has already promised
    // is usable.
    @Test
    void resetPasswordClearsTheLockoutAndSavesTheNewHash() {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(7L);
        token.setUser(seeker(true));
        token.setExpiresAt(NOW.plusMinutes(10));
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        service.resetPassword("whatever-raw-token", "NewPass1word!");

        assertThat(token.getUsedAt()).isEqualTo(NOW);
        verify(loginAttemptService).clearFailuresOn(token.getUser());
        verify(userRepository).save(token.getUser());
        assertThat(token.getUser().getPasswordHash()).isEqualTo("{bcrypt}hashed");
        verify(passwordResetTokenRepository).deleteByUser_IdAndIdNot(42L, 7L);
        verify(activityLogService).log(eq(ActivityType.PASSWORD_CHANGED), eq(token.getUser()),
                contains("reset their password"), eq(TargetType.USER), eq(42L));
    }

    @Test
    void resetPasswordRefusesAnExpiredToken() {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(7L);
        token.setUser(seeker(true));
        token.setExpiresAt(NOW.minusMinutes(1)); // already expired
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.resetPassword("expired-token", "NewPass1word!"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(PasswordResetService.INVALID_TOKEN_MESSAGE);
        verify(userRepository, never()).save(any(User.class));
        verify(loginAttemptService, never()).clearFailuresOn(any(User.class));
    }
}
