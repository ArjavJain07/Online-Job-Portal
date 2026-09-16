package com.jobportal.seed;

import com.jobportal.config.AppProperties;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.SettingsService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Runs at every application start (Section 13.1). Three steps, each idempotent so
// restarting the app never duplicates anything:
// 1. Make sure the system_settings row (id 1) exists.
// 2. Make sure a Site Admin account exists.
// 3. Only the first time (no EMPLOYER accounts yet) and only when app.seed.demo-data is
// on, load the whole Section 13 demo dataset through DemoDataLoader.
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final DemoDataLoader demoDataLoader;
    private final Clock clock;

    public DataSeeder(UserRepository userRepository, SettingsService settingsService,
            PasswordEncoder passwordEncoder, AppProperties appProperties, DemoDataLoader demoDataLoader,
            Clock clock) {
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
        this.demoDataLoader = demoDataLoader;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        settingsService.ensureDefaults();
        ensureAdmin();
        if (appProperties.seed().demoData() && !anyUserWithRole(Role.EMPLOYER)) {
            demoDataLoader.load();
        }
    }

    // Creates the one built-in admin account the first time the app ever starts. Later
    // starts see the account already there and do nothing, whatever app.seed.demo-data is
    // set to (an empty portal still needs someone who can sign in and open it up).
    private void ensureAdmin() {
        if (anyUserWithRole(Role.ADMIN)) {
            return;
        }
        String email = appProperties.seed().adminEmail().trim().toLowerCase(Locale.ROOT);

        User admin = new User();
        admin.setFullName("Site Admin");
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(appProperties.seed().adminPassword()));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        admin.setCreatedAt(LocalDateTime.now(clock));
        userRepository.save(admin);

        log.warn("Default admin created: {}. Change the password after first login.", email);
    }

    private boolean anyUserWithRole(Role role) {
        return userRepository.search(null, role, null, PageRequest.of(0, 1)).getTotalElements() > 0;
    }
}
