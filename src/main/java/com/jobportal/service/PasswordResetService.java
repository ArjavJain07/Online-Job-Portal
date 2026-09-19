package com.jobportal.service;

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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Self-service password reset by emailed token (Section 16 #1, I-17), replacing "an admin
// sets a new password by hand" as the only recovery path. Hard requirement 4 is the point
// of this class; each concern it lists gets its own comment below, next to the code that
// answers it.
//
// ===========================================================================
// The enumeration guarantee this class exists to protect (read before changing anything)
// ===========================================================================
// docs/PROJECT_PLAN.md Section 4.10 built the login lockout so that a locked account is
// indistinguishable from an unknown address - the lockout check runs AFTER the password
// comparison for exactly that reason. A "forgot password" page that answered "no account
// with that email" would hand back precisely the oracle Section 4.10 went to trouble to
// deny: five junk guesses against any address would no longer be needed when one visit to
// THIS page settles it for free. So requestReset() below does the same DB work (or does
// not) behind the scenes and PasswordResetController shows the exact same words either
// way - see that class for the actual response. This class's job is to make sure nothing
// observable from OUTSIDE (response body, redirect target, timing-sensitive early return)
// depends on whether the row was found.
@Service
public class PasswordResetService {

    public static final String INVALID_TOKEN_MESSAGE = "This password reset link is invalid or has expired.";

    // 32 bytes = 256 bits of entropy (hard requirement 4, "token entropy"): a guessing
    // attacker would need to search a space of 2^256 possible values, which no amount of
    // realistic computation gets within reach of, whether they attack one account or every
    // account on the site at once. Base64 URL-safe, no padding, turns that into a 43
    // character string using only [A-Za-z0-9_-] - already exactly the character set a URL
    // query parameter allows, so the link PasswordResetController builds never needs
    // percent-encoding.
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final ActivityLogService activityLogService;
    private final NotificationService notificationService;
    private final PasswordResetProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder, LoginAttemptService loginAttemptService,
            ActivityLogService activityLogService, NotificationService notificationService,
            PasswordResetProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
    }

    // Called by PasswordResetController for every POST /forgot-password, whatever the
    // submitted address turns out to be. resetUrlBase is the "https://host/reset-password"
    // prefix the controller builds from the current request (Section 4.10's own
    // "/login?locked" note explains why: an absolute link has to reflect Render's proxy,
    // via server.forward-headers-strategy, not be guessed at from inside a service).
    //
    // UNCONDITIONAL RETURN, NOT AN EXCEPTION OR A BOOLEAN: this method never tells its
    // caller whether an account was found. Returning void (rather than, say, a boolean
    // the controller could branch on) is deliberate - it removes the temptation to let
    // that information leak into the response one refactor from now.
    @Transactional
    public void requestReset(String rawEmail, String resetUrlBase) {
        String email = normaliseEmail(rawEmail);
        Optional<User> found = userRepository.findByEmail(email);

        // Same outward behaviour whether the address is unknown OR known but disabled.
        // Unlike /login (Section 4.4), which accepts leaking a deactivated account through
        // its own distinct "?blocked" message as a narrow, already-shipped trade-off, this
        // page has no equivalent user-facing benefit to weigh against silence: a disabled
        // account cannot log in with any password, old or new (CurrentUserInterceptor logs
        // it straight back out, Section 4.6), so completing a reset would achieve nothing
        // for it - there is no reason to ever tell this page's caller which case applies.
        if (found.isEmpty() || !found.get().isEnabled()) {
            return;
        }
        User user = found.get();

        // Only the newest link is ever valid: an older, still-unexpired token from an
        // earlier request is removed rather than left live, so a stale email cannot be
        // used once a fresh one has been sent (hard requirement 4, "single use" extended
        // to "single LIVE token", not just "single use of the same one").
        passwordResetTokenRepository.deleteByUser_Id(user.getId());

        String rawToken = newRawToken();
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordResetToken entity = new PasswordResetToken();
        entity.setUser(user);
        entity.setTokenHash(hash(rawToken));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusMinutes(properties.tokenExpiryMinutes()));
        passwordResetTokenRepository.save(entity);

        // rawToken lives only here and inside the email about to be built - never in the
        // database (see PasswordResetToken's class comment) and never in a log line this
        // class writes itself.
        String link = resetUrlBase + "?token=" + rawToken;
        notificationService.notifyPasswordReset(user.getEmail(), user.getFullName(), link,
                properties.tokenExpiryMinutes());
    }

    // GET /reset-password: a read-only preview so the page can decide which of its two
    // states to render (Section 6.1-style page, PasswordResetController) WITHOUT spending
    // the token - only resetPassword() below ever sets usedAt.
    public boolean isValidToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return passwordResetTokenRepository.findByTokenHash(hash(rawToken))
                .map(token -> token.isUsable(LocalDateTime.now(clock)))
                .orElse(false);
    }

    // POST /reset-password. Re-validates the token itself rather than trusting the
    // controller's earlier isValidToken() check (defence in depth against the token
    // expiring, or being used from a second tab, between the GET that rendered the form
    // and this submission - the same "check again at the point of writing" rule
    // JobApplicationService.apply() follows for its own duplicate/Live checks).
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hash(rawToken))
                .filter(t -> t.isUsable(LocalDateTime.now(clock)))
                .orElseThrow(() -> new BusinessRuleException(INVALID_TOKEN_MESSAGE));

        User user = token.getUser();
        token.setUsedAt(LocalDateTime.now(clock));
        passwordResetTokenRepository.save(token);
        // Cleans up a sibling a rare, overlapping pair of /forgot-password submissions
        // could have left behind - see the repository method's own comment for why
        // deleteByUser_Id at request time does not already guarantee this alone.
        passwordResetTokenRepository.deleteByUser_IdAndIdNot(user.getId(), token.getId());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now(clock));
        // Hard requirement 4's last question: what happens to an active login lockout.
        // Completing a reset proves control of the account's registered mailbox, which is
        // at least as strong a proof of identity as the password it is replacing - and
        // forgetting the password is usually WHY an account is locked in the first place
        // (Section 4.10), so the cooldown must not survive a successful reset. Reuses the
        // exact method RoleBasedAuthenticationSuccessHandler calls after an ordinary
        // successful login, rather than clearing the two columns by hand here, so the two
        // places a lockout can end can never quietly drift apart.
        loginAttemptService.clearFailuresOn(user);
        userRepository.save(user);

        // Design rule 3.4: every service that changes data logs it. Reuses the existing
        // PASSWORD_CHANGED type (UserAccountService.changePassword logs the same type for
        // the self-service, logged-in path) rather than adding a new ActivityType: the
        // security-meaningful fact here is identical - this account's password changed -
        // and the description text is what tells the two apart in the admin feed. A
        // separate "reset REQUESTED" event was deliberately not added (see class comment
        // above requestReset): every request already changes data (a token row), but
        // logging attempted addresses the way LOGIN_FAILED does would mean widening
        // activity_logs.type's check constraint, which - unlike this migration's new
        // table - would need dropping the EXISTING constraint by its auto-generated name,
        // a name Section 10.7 itself says must be looked up against the live database
        // rather than assumed, and H2/PostgreSQL are not guaranteed to assign it the same
        // way. Not worth that cross-database risk for a nice-to-have audit row.
        activityLogService.log(ActivityType.PASSWORD_CHANGED, user,
                user.getFullName() + " reset their password using an emailed link", TargetType.USER, user.getId());
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // SHA-256, not BCrypt - see the V3 migration's comment on password_reset_tokens for
    // why an unsalted, fast hash is the right tool for an already-256-bit-random secret
    // that needs to be looked up by exact match, unlike a human-chosen password.
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256 (it is a mandatory MessageDigest algorithm, Java
            // Platform spec); this can only mean a broken JVM installation, which nothing
            // in this method could recover from anyway.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // Same normalisation as AppUserDetailsService/LoginAttemptService (4.2/4.10): stored
    // emails are already trimmed and lower-cased, so the typed value has to be too or a
    // real account's own reset request would look up nothing.
    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
