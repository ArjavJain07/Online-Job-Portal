package com.jobportal.security;

import java.time.LocalDateTime;
import org.springframework.security.authentication.LockedException;

// Thrown by PostAuthenticationLockoutCheck when the right password is presented for an
// account that is still inside its cooldown (Section 4.10). It carries the instant the
// lock lifts so LoginFailureHandler can tell the user when to come back, instead of the
// login page having to look the account up again - which it must never do, because
// /login is served to anonymous callers and any per-email answer there would be an
// account-existence oracle.
//
// It extends LockedException (rather than being a fresh AuthenticationException) so that
// anything reading Spring Security's own exception hierarchy - the authentication event
// publisher, a future audit listener - still classifies it as "account locked".
public class AccountLockedException extends LockedException {

    private final LocalDateTime lockedUntil;

    public AccountLockedException(LocalDateTime lockedUntil) {
        // The message is for logs only. It never reaches the browser: LoginFailureHandler
        // redirects to /login?locked and the wording comes from the template, the same
        // way the ?error and ?blocked messages of 4.4 do.
        super("Account is locked until " + lockedUntil);
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }
}
