package com.jobportal.security;

import com.jobportal.domain.User;
import com.jobportal.repository.UserRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// The only UserDetailsService in the app (Section 4.2: no hand-built
// DaoAuthenticationProvider). Spring Security's default DaoAuthenticationProvider is built
// automatically from this bean plus the PasswordEncoder bean in SecurityConfig.
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // The login form's "email" parameter is looked up case-insensitively (4.2): every
        // stored email is already trimmed and lower-cased (4.3), so normalising the typed
        // value the same way is enough for an exact match.
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalised)
                .orElseThrow(() -> new UsernameNotFoundException("No account with that email."));
        return new AppUserDetails(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole(),
                user.isEnabled());
    }
}
