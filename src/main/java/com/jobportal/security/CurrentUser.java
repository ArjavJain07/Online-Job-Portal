package com.jobportal.security;

import com.jobportal.domain.enums.Role;

// A small, per-request snapshot of the logged-in user, reloaded fresh on every request by
// CurrentUserInterceptor (Section 4.6) and exposed to templates as "currentUser" by
// GlobalModelAttributes. Reloading it (instead of reusing the session's AppUserDetails)
// means a name or role an admin just changed shows up immediately, not after re-login.
public record CurrentUser(Long id, String fullName, String email, Role role, boolean enabled, String companyName) {
}
