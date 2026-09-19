package com.jobportal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Every GET page renders for its role without a Thymeleaf error (Section 12.2, X-2,
// Foundation contract item 10: "add every new GET page to PageRenderSmokeTest"). M1 only
// had the three placeholder dashboards; each module adds its own rows to pages() below as
// it replaces its placeholder with the real page. M2 adds the public pages (P-1, P-2),
// the auth/registration pages (P-3, P-4) and change password (P-5) - a null seederEmail
// means the row is checked anonymously, matching the "Anyone" rows of the route summary
// (Section 6.6): GET /login and GET /register redirect an authenticated visitor away
// (Section 6.1 P-3/P-4), so those two rows must not be attached to a principal.
//
// Authentication is built from the real AppUserDetailsService (via UserDetailsService)
// instead of @WithUserDetails, so the seeded user for each row can be parameterised;
// CurrentUserInterceptor only reloads a real AppUserDetails principal (Section 4.6), so
// this must be the same kind of principal @WithUserDetails would have produced.
class PageRenderSmokeTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    record Page(String url, String seederEmail, String expectedHeading) {
    }

    static Stream<Page> pages() {
        return Stream.of(
                new Page("/admin/dashboard", "admin@jobportal.local", "Admin Dashboard"),
                new Page("/employer/dashboard", "hr@acme.local", "Employer Dashboard"),
                new Page("/seeker/dashboard", "priya@demo.local", "Job Seeker Dashboard"),
                // Public and authentication pages (M2, Section 6.1 P-1 to P-5).
                new Page("/", null, "Find your next job"),
                new Page("/jobs", null, "Find jobs"),
                // J1 Java Developer: seed data codes equal ids on a fresh database (13.1).
                new Page("/jobs/1", null, "Java Developer"),
                new Page("/login", null, "Log in"),
                new Page("/register", null, "Create an account"),
                new Page("/register/seeker", null, "Create your job seeker account"),
                new Page("/register/employer", null, "Create your employer account"),
                new Page("/account/password", "priya@demo.local", "Change password"),
                // Section 16 #1: self-service password reset. The reset-password row
                // deliberately uses a token that does not exist, to prove the "invalid
                // link" state itself renders cleanly - a real token is single-use and
                // consumed the moment a test resets a password, so it could never stay
                // valid for a second GET the way every other row here is reused freely.
                new Page("/forgot-password", null, "Forgot your password?"),
                new Page("/reset-password?token=does-not-exist", null, "Link no longer valid"),
                // Admin module (M3, Section 6.2). User/job ids below are seed codes, which
                // equal ids on a fresh database (Section 13.1), the same rule already used
                // for "/jobs/1" above.
                new Page("/admin/users", "admin@jobportal.local", "Users"),
                new Page("/admin/users/new", "admin@jobportal.local", "Create user"),
                new Page("/admin/users/10/edit", "admin@jobportal.local", "Edit user"), // U10 Karan Singh
                new Page("/admin/users/10/delete", "admin@jobportal.local", "Delete User"),
                new Page("/admin/jobs", "admin@jobportal.local", "Job Approvals"),
                new Page("/admin/jobs/8", "admin@jobportal.local", "Sales Intern"), // J8
                new Page("/admin/settings", "admin@jobportal.local", "Settings"),
                new Page("/admin/activity", "admin@jobportal.local", "Live Activity"),
                // Statistics (M7, Section 6.2 A-D4).
                new Page("/admin/statistics", "admin@jobportal.local", "Statistics"),
                // Employer module (M4, Section 6.3). J1 Java Developer and A1 (Priya's
                // application to it) are seed codes, which equal ids on a fresh database
                // (Section 13.1), the same rule used above for the admin rows.
                new Page("/employer/jobs", "hr@acme.local", "My jobs"),
                new Page("/employer/jobs/new", "hr@acme.local", "Post a job"),
                new Page("/employer/jobs/1/edit", "hr@acme.local", "Edit job"), // J1 Java Developer
                new Page("/employer/jobs/1", "hr@acme.local", "Java Developer"),
                new Page("/employer/jobs/history", "hr@acme.local", "Job posting history"),
                new Page("/employer/applications", "hr@acme.local", "Applications"),
                new Page("/employer/applications/1", "hr@acme.local", "APP-00001"), // A1 Priya/Java Developer
                new Page("/employer/profile", "hr@acme.local", "Company profile"),
                // Messaging and statistics (M6/M7, Section 6.5.1, 6.3 E-D5). A1 (Priya's
                // Java Developer application) already has a message thread (Section 13.6).
                new Page("/employer/messages", "hr@acme.local", "Messages"),
                new Page("/employer/messages/new", "hr@acme.local", "New message"),
                new Page("/employer/messages/1", "hr@acme.local", "Messages"),
                new Page("/employer/statistics", "hr@acme.local", "Statistics"),
                // Job seeker module (M5, Section 6.4). J2 Spring Boot Intern is Live and
                // Priya has never applied to it (Section 13.5), so its apply form renders
                // instead of redirecting; A1 (Priya/Java Developer) is a seed code, equal
                // to its id on a fresh database (Section 13.1).
                new Page("/seeker/jobs", "priya@demo.local", "Find jobs"),
                new Page("/seeker/jobs/2/apply", "priya@demo.local", "Spring Boot Intern"), // J2
                new Page("/seeker/applications", "priya@demo.local", "My applications"),
                new Page("/seeker/applications/history", "priya@demo.local", "Application history"),
                new Page("/seeker/applications/1", "priya@demo.local", "APP-00001"), // A1 Priya/Java Developer
                new Page("/seeker/profile", "priya@demo.local", "My profile"),
                // Messaging and recommendations (M6/M7, Section 6.5.1, 6.4 S-D5). A1 is
                // Priya's own thread (Section 13.6); she has skills, so recommendations
                // are personalised rather than the "Latest jobs" fallback (Section 13.3).
                new Page("/seeker/messages", "priya@demo.local", "Messages"),
                new Page("/seeker/messages/1", "priya@demo.local", "Messages"),
                new Page("/seeker/recommendations", "priya@demo.local", "Recommended for you"));
    }

    @ParameterizedTest(name = "GET {0} renders for {1}")
    @MethodSource("pages")
    void pageRendersWithItsHeading(Page page) throws Exception {
        var request = get(page.url());
        if (page.seederEmail() != null) {
            UserDetails principal = userDetailsService.loadUserByUsername(page.seederEmail());
            request = request.with(user(principal));
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(page.expectedHeading())));
    }
}
