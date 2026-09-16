package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Registration, P-3 (Section 6.1). AC-P3-1 to AC-P3-4.
class RegistrationTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SeekerProfileRepository seekerProfileRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    // AC-P3-1: a seeker registration creates the User (JOB_SEEKER, enabled) and an empty
    // SeekerProfile, logs USER_REGISTERED, and sends the browser to /login?registered.
    @Test
    void seekerRegistrationCreatesUserAndProfile() throws Exception {
        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Ravi Kumar")
                        .param("email", "ravi@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Seeker@123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        User user = userRepository.findByEmail("ravi@demo.local").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.JOB_SEEKER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(seekerProfileRepository.findByUser_Id(user.getId())).isPresent();

        boolean logged = activityLogRepository.findAll().stream()
                .anyMatch(log -> log.getType() == ActivityType.USER_REGISTERED
                        && log.getTargetId().equals(user.getId()));
        assertThat(logged).isTrue();
    }

    // AC-P3-2 (first and second clause): a duplicate email (any case) is rejected and
    // creates nothing; mismatched passwords show their own error.
    @Test
    void duplicateEmailIgnoringCaseRejected() throws Exception {
        long usersBefore = userRepository.count();

        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Priya Duplicate")
                        .param("email", "PRIYA@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Seeker@123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An account with this email already exists.")));

        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Mismatch User")
                        .param("email", "mismatch@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Different@123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passwords do not match.")));

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // AC-P3-2 (third clause): an employer registration with no company name shows the
    // company name error and creates nothing.
    @Test
    void employerNeedsCompanyName() throws Exception {
        long usersBefore = userRepository.count();

        mockMvc.perform(post("/register/employer")
                        .param("fullName", "No Company")
                        .param("email", "nocompany@demo.local")
                        .param("password", "Employer@123")
                        .param("confirmPassword", "Employer@123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please enter your company name (2-120 characters).")));

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // AC-P3-3: with seekerRegistrationOpen = false, both GET and POST render the closed
    // page and create nothing.
    @Test
    void closedRegistrationCreatesNothing() throws Exception {
        setSeekerRegistrationOpen(false);
        long usersBefore = userRepository.count();

        mockMvc.perform(get("/register/seeker"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Job seeker registration is currently closed. Please check back later.")));

        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Too Late")
                        .param("email", "toolate@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Seeker@123")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Job seeker registration is currently closed. Please check back later.")));

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // AC-P3-4: a password with a space, or one that is not printable ASCII (30 Devanagari
    // letters plus a digit, 91 bytes in UTF-8), is rejected with the password message and
    // creates nothing; a normal password such as Seeker@123 is accepted.
    @Test
    void passwordMustBePrintableAscii() throws Exception {
        String passwordMessage = "Password must be 8-64 characters (letters, digits and symbols, "
                + "no spaces) and contain at least one letter and one digit.";

        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Space Password")
                        .param("email", "space@demo.local")
                        .param("password", "Pass word1")
                        .param("confirmPassword", "Pass word1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(passwordMessage)));

        String nonAsciiPassword = "अ".repeat(30) + "1"; // 30 Devanagari letters + a digit
        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Non Ascii Password")
                        .param("email", "nonascii@demo.local")
                        .param("password", nonAsciiPassword)
                        .param("confirmPassword", nonAsciiPassword)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(passwordMessage)));

        assertThat(userRepository.findByEmail("space@demo.local")).isEmpty();
        assertThat(userRepository.findByEmail("nonascii@demo.local")).isEmpty();

        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "Good Password")
                        .param("email", "goodpassword@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Seeker@123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));
    }

    private void setSeekerRegistrationOpen(boolean open) {
        SystemSettings settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setSeekerRegistrationOpen(open);
        systemSettingsRepository.saveAndFlush(settings);
    }
}
