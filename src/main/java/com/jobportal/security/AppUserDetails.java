package com.jobportal.security;

import com.jobportal.domain.enums.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// What Spring Security stores in the session after login (Section 4.6). Deliberately
// small: id, email, the password hash and role/enabled, nothing else, so the session stays
// light and DevTools can serialise it across restarts. Per-request freshness (name, a
// changed role, a deactivated account) is handled separately by CurrentUserInterceptor,
// not by this class.
public class AppUserDetails implements UserDetails, CredentialsContainer {

    private final Long id;
    private final String email;
    private String passwordHash;
    private final Role role;
    private final boolean enabled;

    public AppUserDetails(Long id, String email, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole("ADMIN") checks for authority "ROLE_ADMIN", so the "ROLE_" prefix is
        // added here rather than stored on the Role enum itself.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    // Hard-coded true even though Section 4.10 gives accounts a brute-force lockout, and
    // that is the deliberate part. Spring asks this question in its PRE-authentication
    // checks, before the password is compared, so an honest answer here would tell any
    // anonymous caller "this address is locked" - which is to say "this address exists" -
    // after five junk guesses against any email in the database. The lockout is enforced
    // one step later instead, by PostAuthenticationLockoutCheck, which only runs once the
    // presented password has matched; see the reasoning in that class.
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // Called by Spring Security right after authentication succeeds, so the password hash
    // does not sit in the session for the rest of the login (Section 4.8).
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
