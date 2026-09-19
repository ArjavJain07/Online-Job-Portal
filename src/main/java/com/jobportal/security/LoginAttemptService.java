package com.jobportal.security;

import com.jobportal.config.LockoutProperties;
import com.jobportal.domain.User;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Consecutive failed-login counting and the temporary lockout of Section 4.10. Called
// from LoginFailureHandler (a failure), RoleBasedAuthenticationSuccessHandler (a success)
// and PostAuthenticationLockoutCheck (the enforcement point).
//
// ---------------------------------------------------------------------------
// Per account, not per IP
// ---------------------------------------------------------------------------
// The counter hangs off the users row, keyed by the account being attacked, and nothing
// here looks at the client address. Deliberate:
//   - The app sits behind a reverse proxy and only the prod profile sets
//     server.forward-headers-strategy=framework (Section 10), so outside prod the remote
//     address is the proxy, and every visitor would share one counter. Worse, an
//     X-Forwarded-For header is attacker-controlled input the moment anything can reach
//     the app port directly: a bot would rotate the header to keep its per-IP counter at
//     zero, and could instead forge a victim's address to lock that victim out. A
//     security decision must not be keyed on a value the attacker writes.
//   - Shared addresses (office NAT, mobile carriers) would punish innocent users.
//   - Per-IP throttling still belongs in the stack, but at the nginx layer, which sees
//     the real socket peer and cannot be lied to: limit_req on POST /login. That is
//     deployment configuration, not application code, and is recorded in Section 4.10.
// What per-account tracking does not stop is password spraying - one common password
// tried against many different accounts - because no single account reaches the
// threshold. That is the known gap, and it is the gap nginx's per-IP limit covers.
//
// ---------------------------------------------------------------------------
// In the database, not in memory
// ---------------------------------------------------------------------------
// A ConcurrentHashMap would avoid a write per failed login, but the hosted instance is a
// free Render web service that sleeps after 15 minutes idle and is replaced on every
// deploy (render.yaml). An in-memory counter is wiped each time, so a patient bot would
// get an unlimited number of fresh five-guess windows just by pausing. Two columns on
// users survive a restart, cost one indexed UPDATE on a path that already writes an
// activity_log row for every failed login, and are visible to an admin in the database.
//
// Known limit: recordFailure is a read-modify-write with no row lock, so two failures
// landing at the same instant can both read the same count and one increment is lost.
// That costs an attacker at most a guess or two per lockout window and cannot skip the
// lockout, because the next single-threaded failure still reaches the threshold. A
// SELECT ... FOR UPDATE would close it and would serialise every failed login on one row,
// which is a better denial-of-service target than the thing it fixes.
@Service
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final Clock clock;
    private final LockoutProperties properties;

    public LoginAttemptService(UserRepository userRepository, Clock clock, LockoutProperties properties) {
        this.userRepository = userRepository;
        this.clock = clock;
        this.properties = properties;
    }

    // The instant the lock lifts, or empty when the account is not locked right now.
    // Only ever called after the presented password has already been verified - see the
    // comment in PostAuthenticationLockoutCheck.
    public Optional<LocalDateTime> activeLockFor(String email) {
        return userRepository.findByEmail(normalise(email)).flatMap(this::activeLock);
    }

    public Optional<LocalDateTime> activeLock(User user) {
        LocalDateTime until = user.getLockoutUntil();
        return until != null && until.isAfter(LocalDateTime.now(clock)) ? Optional.of(until) : Optional.empty();
    }

    // One more consecutive failure for this account. An email that belongs to no account
    // does nothing at all - there is no row to count against, and inventing one would
    // hand an attacker a place to look up whether an address is registered.
    @Transactional
    public void recordFailure(String email) {
        userRepository.findByEmail(normalise(email)).ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now(clock);

            // Already locked: do not count, and above all do not push lockoutUntil
            // further out. A lockout that is extended by every new failure is a
            // denial-of-service switch - anyone who knows an email could keep its owner
            // permanently locked out by hammering the form. Here the cooldown always
            // ends at the instant it was first set, whatever the bot does meanwhile.
            if (activeLock(user).isPresent()) {
                return;
            }

            // A non-null lockoutUntil that survived the check above is an expired
            // lockout, so the cooldown has been served: the user starts again from zero
            // rather than being re-locked by their next single mistake.
            int attempts = (user.getLockoutUntil() != null ? 0 : user.getFailedLoginAttempts()) + 1;
            user.setFailedLoginAttempts(attempts);
            user.setLockoutUntil(attempts >= properties.maxAttempts()
                    ? now.plusMinutes(properties.cooldownMinutes())
                    : null);
            userRepository.save(user);
        });
    }

    // Wipes the counter after a successful login ("consecutive" failures only).
    // Mutates the entity and does not save it: the one caller,
    // RoleBasedAuthenticationSuccessHandler, is about to save the same instance for
    // lastLoginAt anyway, so a successful login still costs exactly one UPDATE.
    public void clearFailuresOn(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockoutUntil(null);
    }

    // Same normalisation as AppUserDetailsService (4.2/4.3): stored emails are already
    // trimmed and lower-cased, so the typed value has to be too or "Priya@Demo.local"
    // would get a counter of its own and never lock anything.
    private String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
