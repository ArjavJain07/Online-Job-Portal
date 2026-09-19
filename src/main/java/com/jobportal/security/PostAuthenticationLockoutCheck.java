package com.jobportal.security;

import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.stereotype.Component;

// Refuses a login whose account is inside its lockout cooldown (Section 4.10). Wired into
// DaoAuthenticationProvider#setPostAuthenticationChecks by SecurityConfig.
//
// ===========================================================================
// Why POST-authentication, and why this class exists at all
// ===========================================================================
// The obvious implementation is one line: make AppUserDetails#isAccountNonLocked() return
// whether the account is locked, and let Spring throw LockedException for us. That line
// would be an account-enumeration oracle, and it is the reason this file is here.
//
// AbstractUserDetailsAuthenticationProvider#authenticate runs
//   1. retrieveUser              - load the account (or fail as bad credentials)
//   2. preAuthenticationChecks   - isAccountNonLocked, isEnabled, isAccountNonExpired
//   3. additionalAuthenticationChecks - compare the BCrypt hash
//   4. postAuthenticationChecks  - THIS CLASS
// so isAccountNonLocked() is answered BEFORE the password is looked at. Section 4.4
// already documents what that costs: isEnabled() sits at step 2, which is why a
// deactivated account shows "/login?blocked" for any password at all and thereby reveals
// that the address is registered. That leak is an accepted trade-off there because it is
// limited to the handful of accounts an admin has deactivated.
//
// Putting the lockout at step 2 would turn the same leak into a general-purpose oracle
// against EVERY address in the database: send five junk passwords to any email, then a
// sixth, and a "this account is locked" answer means the address exists while
// "invalid email or password" means it does not. Five cheap requests per address, and the
// site would be enumerable end to end - strictly worse than the trade-off 4.4 accepted,
// which is exactly what this feature was told not to do.
//
// At step 4 the check only runs once the presented password has already matched the
// stored hash. So:
//   wrong password + locked account  -> BadCredentialsException at step 3, "/login?error"
//                                       - byte for byte the answer an unknown address
//                                         gets, so nothing is revealed;
//   right password + locked account  -> AccountLockedException here, "/login?locked"
//                                       - told to someone who has just proved they know
//                                         the account's password, so the message reveals
//                                         nothing they did not already know;
//   unknown address                  -> BadCredentialsException at step 1, "/login?error".
// The lockout is still fully enforced in every case: knowing the right password during a
// cooldown does not get anyone a session.
//
// The trade-off this direction costs us: a real user who has forgotten their password and
// locked themselves out keeps seeing the generic "Invalid email or password." until they
// happen to type the right one, and only then learns about the cooldown. A friendlier
// "your account is locked, try again in 12 minutes" shown on any wrong password would be
// the enumeration oracle described above. Not leaking which addresses are registered wins;
// the friendlier message is not worth handing out the user list.
@Component
public class PostAuthenticationLockoutCheck implements UserDetailsChecker {

    private final LoginAttemptService loginAttemptService;

    public PostAuthenticationLockoutCheck(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void check(UserDetails user) {
        // One extra query, on the rare path where a password actually matched - not on
        // the flood of wrong guesses, which never reach step 4. The lock state is read
        // here rather than carried on AppUserDetails because AppUserDetails is what lives
        // in the HTTP session for the next hour (4.6), and a cooldown that expires two
        // minutes after login has no business being cached there.
        loginAttemptService.activeLockFor(user.getUsername()).ifPresent(until -> {
            throw new AccountLockedException(until);
        });

        // Setting postAuthenticationChecks replaces Spring's DefaultPostAuthenticationChecks,
        // which had exactly one rule. It is kept here so wiring in the lockout does not
        // quietly drop a stock account-status check.
        if (!user.isCredentialsNonExpired()) {
            throw new CredentialsExpiredException("User credentials have expired");
        }
    }
}
