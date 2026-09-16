package com.jobportal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

// Login, logout and the role redirect, P-4 (Section 6.1), plus the first half of P-2's
// "log in to apply" flow. AC-P4-1 to AC-P4-3; the anonymous-redirect half of AC-P2-2 (the
// apply-form landing itself is M5, AuthFlowTest#loginToApplyReturnsToApplyForm).
class AuthFlowTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private Clock clock;

    // AC-P4-1: each seeded role lands on its own dashboard after logging in.
    @Test
    void loginRedirectsEachRoleToOwnDashboard() throws Exception {
        assertLoginRedirectsTo("admin@jobportal.local", "Admin@123", "/admin/dashboard");
        assertLoginRedirectsTo("hr@acme.local", "Employer@123", "/employer/dashboard");
        assertLoginRedirectsTo("priya@demo.local", "Seeker@123", "/seeker/dashboard");
    }

    // AC-P4-1: lastLoginAt is set to the current (fixed clock) time on a successful login.
    @Test
    void lastLoginAtUpdated() throws Exception {
        login("priya@demo.local", "Seeker@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection());

        User priya = userRepository.findByEmail("priya@demo.local").orElseThrow();
        assertThat(priya.getLastLoginAt()).isEqualTo(LocalDateTime.now(clock));
    }

    // AC-P4-2 (first clause): a wrong password and an unknown email both show the same
    // generic message and both log LOGIN_FAILED.
    @Test
    void badCredentialsShowGenericError() throws Exception {
        long failuresBefore = countLoginFailures();

        login("priya@demo.local", "WrongPassword1", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        login("unknown@demo.local", "Whatever1", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("Invalid email or password.")));

        assertThat(countLoginFailures()).isEqualTo(failuresBefore + 2);
    }

    // AC-P4-2 (second clause): the deactivated QuickHire account is told it is
    // deactivated whether the password is right or wrong (4.4's accepted trade-off), and
    // both attempts log LOGIN_FAILED.
    @Test
    void disabledUserCannotLogin() throws Exception {
        long failuresBefore = countLoginFailures();

        login("jobs@quickhire.local", "Employer@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?blocked"));

        login("jobs@quickhire.local", "WrongPassword1", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?blocked"));

        mockMvc.perform(get("/login").param("blocked", ""))
                .andExpect(content().string(
                        containsString("Your account has been deactivated. Please contact the administrator.")));

        assertThat(countLoginFailures()).isEqualTo(failuresBefore + 2);
    }

    // AC-P4-3: after logout, a protected page redirects to /login again.
    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login("priya@demo.local", "Seeker@123", session)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/dashboard"));

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        // LoginUrlAuthenticationEntryPoint builds a full absolute URL, not a
        // context-relative one (see AccessControlTest#anonymousRedirectedToLoginForEachZone).
        mockMvc.perform(get("/seeker/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // First half of AC-P2-2: an anonymous request into the seeker URL zone is saved and
    // redirected to /login; logging in with the WRONG role for that saved request is sent
    // to that role's own dashboard instead (RoleBasedAuthenticationSuccessHandler,
    // Section 4.4). The apply route itself does not exist until M5, but the zone rule and
    // the saved-request handling can already be proven.
    @Test
    void savedRequestIgnoredForWrongRole() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/seeker/jobs/2/apply").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        login("hr@acme.local", "Employer@123", session)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/dashboard"));
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }

    private void assertLoginRedirectsTo(String email, String password, String expectedDashboard) throws Exception {
        login(email, password, new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedDashboard));
    }

    private long countLoginFailures() {
        return activityLogRepository.findAll().stream()
                .filter(log -> log.getType() == ActivityType.LOGIN_FAILED)
                .count();
    }
}
