package com.jobportal.security;

import com.jobportal.domain.enums.Role;

// The dashboard URL and URL-zone prefix for each role, defined once here instead of being
// scattered across controllers (used by RoleBasedAuthenticationSuccessHandler and
// HomeController#dashboard, Section 4.4).
public final class RoleRoutes {

    private RoleRoutes() {
    }

    public static String dashboardUrl(Role role) {
        return switch (role) {
            case ADMIN -> "/admin/dashboard";
            case EMPLOYER -> "/employer/dashboard";
            case JOB_SEEKER -> "/seeker/dashboard";
        };
    }

    public static String zonePrefix(Role role) {
        return switch (role) {
            case ADMIN -> "/admin/";
            case EMPLOYER -> "/employer/";
            case JOB_SEEKER -> "/seeker/";
        };
    }
}
