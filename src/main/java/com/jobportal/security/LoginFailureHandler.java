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
// password) and picks the message: a deactivated account is told so, everything else -
// unknown email or wrong password - gets one generic message so an attacker cannot tell
// which was wrong.
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final ActivityLogService activityLogService;

    public LoginFailureHandler(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        String rawEmail = request.getParameter("email");
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        boolean blocked = exception instanceof DisabledException;

        String description = blocked
                ? "Failed login attempt for " + email + " (account deactivated)"
                : "Failed login attempt for " + email;
        activityLogService.log(ActivityType.LOGIN_FAILED, null, description, null, null);

        response.sendRedirect(request.getContextPath() + (blocked ? "/login?blocked" : "/login?error"));
    }
}
