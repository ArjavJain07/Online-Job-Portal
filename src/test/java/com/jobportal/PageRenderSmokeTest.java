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
                new Page("/account/password", "priya@demo.local", "Change password"));
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
