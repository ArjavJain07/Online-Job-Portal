package com.jobportal.security;

import com.jobportal.repository.UserRepository;
import com.jobportal.repository.projection.CurrentUserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

// Fixes the gap described in Section 4.6: AppUserDetails is frozen in the session at
// login, so an admin's later change (deactivate, rename, change role) would not reach an
// already logged-in user by itself. This interceptor reloads a few columns by id on every
// request and reacts if they no longer match.
@Component
public class CurrentUserInterceptor implements HandlerInterceptor {

    // Request attribute name GlobalModelAttributes reads to expose "currentUser".
    public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

    private final UserRepository userRepository;

    public CurrentUserInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails principal)) {
            return true; // anonymous request: nothing to reload
        }

        CurrentUserView view = userRepository.findProjectedById(principal.getId()).orElse(null);
        if (view == null || !view.isEnabled()) {
            request.logout();
            response.sendRedirect(request.getContextPath() + "/login?blocked");
            return false;
        }
        if (view.getRole() != principal.getRole() || !view.getEmail().equalsIgnoreCase(principal.getUsername())) {
            request.logout();
            response.sendRedirect(request.getContextPath() + "/login?changed");
            return false;
        }

        request.setAttribute(CURRENT_USER_ATTRIBUTE,
                new CurrentUser(view.getId(), view.getFullName(), view.getEmail(), view.getRole(), view.isEnabled(),
                        view.getCompanyName()));
        return true;
    }
}
