package com.jobportal.security;

import com.jobportal.domain.enums.ActivityType;
import com.jobportal.service.ActivityLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

// Runs after a failed login (Section 4.4). Logs the attempt (email only, never the
// password), counts it towards the lockout of Section 4.10, and picks the message: a
// deactivated account is told so, an account whose cooldown is running is told so ONLY
// because the password it just sent was correct, and everything else - unknown email or
// wrong password - gets one generic message so an attacker cannot tell which was wrong.
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    // Where the unlock instant is parked for the /login render that follows the redirect.
    // It goes in the session, not in the query string: a URL like /login?locked&until=...
    // could be crafted and sent to anyone, and the page would dutifully repeat whatever
    // time the link said. In the session it can only have been put there by this handler,
    // after a correct password. AuthController reads it once and removes it.
    public static final String LOCKED_UNTIL_ATTRIBUTE = "loginLockoutUntil";

    private final ActivityLogService activityLogService;
    private final LoginAttemptService loginAttemptService;

    public LoginFailureHandler(ActivityLogService activityLogService, LoginAttemptService loginAttemptService) {
        this.activityLogService = activityLogService;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        String rawEmail = request.getParameter("email");
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);

        // Right password, cooldown still running (thrown by PostAuthenticationLockoutCheck
        // and never reachable without the correct password). This is the one caller that
        // may be told the account is locked, and the only one that gets a time.
        if (exception instanceof AccountLockedException locked) {
            activityLogService.log(ActivityType.LOGIN_FAILED, null,
                    "Failed login attempt for " + email + " (account locked)", null, null);
            request.getSession().setAttribute(LOCKED_UNTIL_ATTRIBUTE, locked.getLockedUntil());
            response.sendRedirect(request.getContextPath() + "/login?locked");
            return;
        }

        boolean blocked = exception instanceof DisabledException;

        // Count the failure - but not a DisabledException, which Spring raises before it
        // ever looks at the password (4.4). Counting those would let anyone lock a
        // deactivated account that is already barred from logging in, and would make the
        // counter mean "attempts" rather than "wrong passwords".
        if (!blocked) {
            loginAttemptService.recordFailure(email);
        }

        String description = blocked
                ? "Failed login attempt for " + email + " (account deactivated)"
                : "Failed login attempt for " + email;
        activityLogService.log(ActivityType.LOGIN_FAILED, null, description, null, null);

        // Note what is NOT here: no hint that the account has just been locked, no
        // remaining-attempts count. The response for a wrong password against a locked
        // account is identical to the response for an address that has never existed,
        // which is what keeps the lockout from becoming an enumeration oracle.
        response.sendRedirect(request.getContextPath() + (blocked ? "/login?blocked" : "/login?error"));
    }
}
