package com.jobportal.security;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.ActivityLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

// Runs after every successful login (Section 4.4). Records the login, then decides where
// to send the user: back to the page they originally asked for (if it is public or inside
// their own role zone - "Log in to apply"), otherwise their role dashboard.
@Component
public class RoleBasedAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public RoleBasedAuthenticationSuccessHandler(UserRepository userRepository,
            ActivityLogService activityLogService, Clock clock) {
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        AppUserDetails me = (AppUserDetails) authentication.getPrincipal();

        User user = userRepository.findById(me.getId())
                .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists: " + me.getId()));
        user.setLastLoginAt(LocalDateTime.now(clock));
        userRepository.save(user);
        activityLogService.log(ActivityType.LOGIN_SUCCESS, user, user.getFullName() + " logged in",
                TargetType.USER, user.getId());

        String targetUrl = resolveTargetUrl(request, response, me);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String resolveTargetUrl(HttpServletRequest request, HttpServletResponse response, AppUserDetails me) {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            // The saved URL carries Spring Security 6's "?continue" marker, so only the
            // path (not the whole string) is compared against the role zone (4.4).
            String path = URI.create(saved.getRedirectUrl()).getPath();
            if (path.startsWith(RoleRoutes.zonePrefix(me.getRole())) || isPublicPath(path)) {
                return saved.getRedirectUrl();
            }
        }
        return RoleRoutes.dashboardUrl(me.getRole());
    }

    private boolean isPublicPath(String path) {
        return path.equals("/") || path.equals("/jobs") || path.startsWith("/jobs/");
    }
}
