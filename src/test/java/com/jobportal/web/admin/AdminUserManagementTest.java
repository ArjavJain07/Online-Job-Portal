package com.jobportal.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.Role;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.UserService;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.web.form.UserForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.ResultActions;

// Admin user management (Section 6.2 A-F1/A-D1). Every request is authenticated with the
// real AppUserDetailsService principal via SecurityMockMvcRequestPostProcessors.user(...)
// (Section 12.1), which - unlike a class-level @WithUserDetails - only applies to the one
// request it decorates, so a test can freely mix it with a genuine POST /login (used
// wherever a password actually needs to be checked, the same pattern as AccountTest).
class AdminUserManagementTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SeekerProfileRepository seekerProfileRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private UserService userService;

    // AC-A-F1-1: sets enabled/role/password, creates the row, logs USER_CREATED (checked
    // indirectly through the flash and the resulting login) and the new account can log in.
    @Test
    void createUserShowsConfirmationAndCanLogin() throws Exception {
        UserDetails admin = admin();

        mockMvc.perform(post("/admin/users").with(user(admin)).with(csrf())
                        .param("fullName", "Test User")
                        .param("email", "test.user@demo.local")
                        .param("role", "JOB_SEEKER")
                        .param("enabled", "true")
                        .param("newPassword", "Temp@1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Test User created."));

        User created = userRepository.findByEmail("test.user@demo.local").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.JOB_SEEKER);
        assertThat(created.isEnabled()).isTrue();
        assertThat(seekerProfileRepository.findByUser_Id(created.getId())).isPresent();

        login("test.user@demo.local", "Temp@1234", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/dashboard"));
    }

    // AC-A-F1-2 (first clause): an email that already exists, ignoring case, is refused
    // and nothing is created.
    @Test
    void duplicateEmailRejected() throws Exception {
        UserDetails admin = admin();
        long usersBefore = userRepository.count();

        mockMvc.perform(post("/admin/users").with(user(admin)).with(csrf())
                        .param("fullName", "Dup User")
                        .param("email", "PRIYA@demo.local")
                        .param("role", "JOB_SEEKER")
                        .param("enabled", "true")
                        .param("newPassword", "Temp@1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An account with this email already exists.")));

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // AC-A-F1-2 (second clause): an employer with no company name is refused and nothing
    // is created.
    @Test
    void employerRequiresCompanyName() throws Exception {
        UserDetails admin = admin();
        long usersBefore = userRepository.count();

        mockMvc.perform(post("/admin/users").with(user(admin)).with(csrf())
                        .param("fullName", "No Company Admin")
                        .param("email", "nocompanyadmin@demo.local")
                        .param("role", "EMPLOYER")
                        .param("enabled", "true")
                        .param("newPassword", "Temp@1234"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Company name is required for employer accounts.")));

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // AC-A-F1-3: Karan Singh has no dependencies and can be deleted; Priya Sharma has 4
    // applications and 3 messages, so her confirmation page is blocked and a direct POST
    // is refused with the same message (Section 5.8).
    @Test
    void deleteAllowedOnlyWithoutDependencies() throws Exception {
        UserDetails admin = admin();
        Long karanId = data.userId("karan@demo.local");
        Long priyaId = data.userId("priya@demo.local");
        String blockedMessage = "This user has 4 applications and 3 messages. Deactivate the account instead.";

        mockMvc.perform(get("/admin/users/{id}/delete", karanId).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delete permanently")));

        mockMvc.perform(post("/admin/users/{id}/delete", karanId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Karan Singh deleted."));
        assertThat(userRepository.findById(karanId)).isEmpty();

        mockMvc.perform(get("/admin/users/{id}/delete", priyaId).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(blockedMessage)))
                .andExpect(content().string(containsString("Deactivate instead")));

        mockMvc.perform(post("/admin/users/{id}/delete", priyaId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", blockedMessage));
        assertThat(userRepository.findById(priyaId)).isPresent();
    }

    // AC-A-F1-4 (first clause): deactivating Priya makes her very next request redirect to
    // /login?blocked. CurrentUserInterceptor reloads the row by id on every request rather
    // than trusting the session, so a request authenticated with her (now stale) principal
    // is enough to prove this - no real login/session round trip is needed.
    @Test
    void deactivatedUserLoggedOutOnNextRequest() throws Exception {
        UserDetails admin = admin();
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long priyaId = data.userId("priya@demo.local");

        mockMvc.perform(post("/admin/users/{id}/toggle-status", priyaId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Priya Sharma deactivated."));

        mockMvc.perform(get("/seeker/dashboard").with(user(priya)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?blocked"));
    }

    // Admin half of AC-S-F1-3 (Section 11.2 M2 "deliberately not part of M2", finished
    // here): deactivating Globex Retail removes its Live jobs (Data Analyst, Marketing
    // Executive, Section 13.4) from the public /jobs list - JobSpecifications.live() also
    // checks the employer's enabled flag (11.3 contract item 4). The seeker's refused
    // apply POST is the other half, covered once /seeker/jobs/{id}/apply exists in M5.
    @Test
    void deactivatingEmployerHidesItsJobsFromPublicBrowse() throws Exception {
        UserDetails admin = admin();
        Long globexId = data.userId("talent@globex.local");

        mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Data Analyst")))
                .andExpect(content().string(containsString("Marketing Executive")));

        mockMvc.perform(post("/admin/users/{id}/toggle-status", globexId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "User Globex Retail (Vikram Nair) deactivated."));

        String afterDeactivation = mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterDeactivation).doesNotContain("Data Analyst", "Marketing Executive");

        // Reactivating restores them (mentioned alongside AC-S-F1-3 in manual check A-09).
        mockMvc.perform(post("/admin/users/{id}/toggle-status", globexId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "User Globex Retail (Vikram Nair) activated."));

        String afterReactivation = mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterReactivation).contains("Data Analyst", "Marketing Executive");
    }

    // AC-A-F1-4 (second clause) plus rule 4 of Section 6.2 A-F1: the admin can't touch
    // their own role/status/account, and the last active admin can't be deactivated,
    // deleted or role-changed by anyone. The self checks are proven over HTTP; the
    // last-admin check is proven directly against UserService, because reaching it
    // legitimately needs a second acting admin account whose session the running
    // application would never let become "the acting user" once it is the sole survivor -
    // UserService.toggleStatus/delete/update take the acting id as a plain parameter
    // (Section 11.3 item 5), so the rule itself can still be exercised precisely.
    @Test
    void selfAndLastAdminProtected() throws Exception {
        UserDetails admin = admin();
        Long adminId = data.userId("admin@jobportal.local");

        mockMvc.perform(post("/admin/users/{id}", adminId).with(user(admin)).with(csrf())
                        .param("fullName", "Site Admin")
                        .param("email", "admin@jobportal.local")
                        .param("role", "EMPLOYER")
                        .param("companyName", "Whatever")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(htmlEscaped(UserService.SELF_ROLE_OR_STATUS_MESSAGE))));

        mockMvc.perform(post("/admin/users/{id}/toggle-status", adminId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", UserService.SELF_DELETE_OR_DEACTIVATE_MESSAGE));

        mockMvc.perform(post("/admin/users/{id}/delete", adminId).with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", UserService.SELF_DELETE_OR_DEACTIVATE_MESSAGE));

        User backupAdmin = userService.create(adminForm("Backup Admin", "backup2@jobportal.local", "Temp@1234"), adminId);
        assertThat(backupAdmin.getRole()).isEqualTo(Role.ADMIN);

        // Two active admins: deactivating the other one (not self) is allowed.
        userService.toggleStatus(backupAdmin.getId(), adminId);
        assertThat(userRepository.findById(backupAdmin.getId()).orElseThrow().isEnabled()).isFalse();

        // Only one active admin is left (the original Site Admin). Acting "as" the now
        // disabled backup admin, every action against that last active admin is refused.
        assertThatThrownBy(() -> userService.toggleStatus(adminId, backupAdmin.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(UserService.LAST_ADMIN_MESSAGE);
        assertThatThrownBy(() -> userService.delete(adminId, backupAdmin.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(UserService.LAST_ADMIN_MESSAGE);
        UserForm demoteToEmployer = new UserForm();
        demoteToEmployer.setFullName("Site Admin");
        demoteToEmployer.setEmail("admin@jobportal.local");
        demoteToEmployer.setRole(Role.EMPLOYER);
        demoteToEmployer.setCompanyName("Whatever");
        demoteToEmployer.setEnabled(true);
        assertThatThrownBy(() -> userService.update(adminId, demoteToEmployer, backupAdmin.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(UserService.LAST_ADMIN_MESSAGE);
    }

    // AC-A-F1-5: a new password alone updates the flash wording and works immediately (the
    // old password stops working); saving again with a blank password and a changed name
    // shows the plain flash and the new name is what gets saved.
    @Test
    void updateUserShowsConfirmation() throws Exception {
        UserDetails admin = admin();
        Long arjunId = data.userId("arjun@demo.local");

        mockMvc.perform(post("/admin/users/{id}", arjunId).with(user(admin)).with(csrf())
                        .param("fullName", "Arjun Mehta")
                        .param("email", "arjun@demo.local")
                        .param("role", "JOB_SEEKER")
                        .param("enabled", "true")
                        .param("newPassword", "Reset@123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Arjun Mehta updated. The new password works immediately."));

        boolean logged = activityLogRepository.findAll().stream()
                .anyMatch(l -> l.getType() == ActivityType.USER_UPDATED && arjunId.equals(l.getTargetId()));
        assertThat(logged).isTrue();

        login("arjun@demo.local", "Reset@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/dashboard"));
        login("arjun@demo.local", "Seeker@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(post("/admin/users/{id}", arjunId).with(user(admin)).with(csrf())
                        .param("fullName", "Arjun K. Mehta")
                        .param("email", "arjun@demo.local")
                        .param("role", "JOB_SEEKER")
                        .param("enabled", "true")
                        .param("newPassword", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "User Arjun K. Mehta updated."));

        assertThat(userRepository.findById(arjunId).orElseThrow().getFullName()).isEqualTo("Arjun K. Mehta");
    }

    // Section 6.2 A-F1 rule 3: the admin changing their OWN email is logged out to
    // /login?emailChanged (not /login?changed, the wording CurrentUserInterceptor would
    // otherwise use), and the new email works; changing only the name stays logged in.
    @Test
    void ownEmailChangeForcesRelogin() throws Exception {
        UserDetails admin = admin();
        Long adminId = data.userId("admin@jobportal.local");

        mockMvc.perform(post("/admin/users/{id}", adminId).with(user(admin)).with(csrf())
                        .param("fullName", "Site Admin")
                        .param("email", "admin2@jobportal.local")
                        .param("role", "ADMIN")
                        .param("enabled", "true")
                        .param("newPassword", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?emailChanged"));

        login("admin2@jobportal.local", "Admin@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));

        UserDetails admin2 = userDetailsService.loadUserByUsername("admin2@jobportal.local");
        mockMvc.perform(post("/admin/users/{id}", adminId).with(user(admin2)).with(csrf())
                        .param("fullName", "Site Administrator")
                        .param("email", "admin2@jobportal.local")
                        .param("role", "ADMIN")
                        .param("enabled", "true")
                        .param("newPassword", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Site Administrator updated."));
    }

    // AC-A-F1-6 (first clause): Neha has no jobs, applications or messages, so her role
    // change to EMPLOYER is allowed, her SeekerProfile is removed and she reaches the
    // employer dashboard afterwards.
    @Test
    void roleChangeAllowedWithoutActivity() throws Exception {
        UserDetails admin = admin();
        Long nehaId = data.userId("neha@demo.local");

        mockMvc.perform(post("/admin/users/{id}", nehaId).with(user(admin)).with(csrf())
                        .param("fullName", "Neha Verma")
                        .param("email", "neha@demo.local")
                        .param("role", "EMPLOYER")
                        .param("companyName", "Test Co")
                        .param("enabled", "true")
                        .param("newPassword", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("success", "User Neha Verma updated."));

        assertThat(seekerProfileRepository.findByUser_Id(nehaId)).isEmpty();
        assertThat(userRepository.search(null, Role.EMPLOYER, null, Pageable.unpaged()).getTotalElements())
                .isEqualTo(4);

        login("neha@demo.local", "Seeker@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/dashboard"));
    }

    // AC-A-F1-6 (second clause): the same change for Priya (who has applications and
    // messages) is refused and her role is unchanged.
    @Test
    void roleChangeBlockedWhenUserHasActivity() throws Exception {
        UserDetails admin = admin();
        Long priyaId = data.userId("priya@demo.local");

        mockMvc.perform(post("/admin/users/{id}", priyaId).with(user(admin)).with(csrf())
                        .param("fullName", "Priya Sharma")
                        .param("email", "priya@demo.local")
                        .param("role", "EMPLOYER")
                        .param("companyName", "Test Co")
                        .param("enabled", "true")
                        .param("newPassword", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(htmlEscaped(UserService.ROLE_CHANGE_BLOCKED_MESSAGE))));

        assertThat(userRepository.findById(priyaId).orElseThrow().getRole()).isEqualTo(Role.JOB_SEEKER);
    }

    // AC-A-D1-1: filtering by role and by a free-text query, and the admin's own row
    // showing neither a Delete nor a Deactivate/Activate action.
    @Test
    void tableFiltersByRoleAndQuery() throws Exception {
        UserDetails admin = admin();

        String employerPage = mockMvc.perform(get("/admin/users").param("role", "EMPLOYER").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(employerPage).contains("Acme Technologies", "Globex Retail", "QuickHire Staffing", "Inactive");
        assertThat(employerPage).doesNotContain("Priya Sharma");

        String queryPage = mockMvc.perform(get("/admin/users").param("q", "globex").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(queryPage).contains("Globex Retail");
        assertThat(queryPage).doesNotContain("Acme Technologies", "QuickHire Staffing");

        Long adminId = data.userId("admin@jobportal.local");
        String allPage = mockMvc.perform(get("/admin/users").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(allPage).doesNotContain("/admin/users/" + adminId + "/delete");
        assertThat(allPage).doesNotContain("/admin/users/" + adminId + "/toggle-status");
    }

    // th:text HTML-escapes an apostrophe as "&#39;" (Section 7.1: "user text only via
    // th:text"), so a business-rule message rendered on a page has to be matched in its
    // escaped form, unlike a flash attribute (compared as the raw Java string, never
    // passed through Thymeleaf's escaping).
    private String htmlEscaped(String message) {
        return message.replace("'", "&#39;");
    }

    private UserForm adminForm(String fullName, String email, String password) {
        UserForm form = new UserForm();
        form.setFullName(fullName);
        form.setEmail(email);
        form.setRole(Role.ADMIN);
        form.setEnabled(true);
        form.setNewPassword(password);
        return form;
    }

    private UserDetails admin() {
        return userDetailsService.loadUserByUsername("admin@jobportal.local");
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }
}
