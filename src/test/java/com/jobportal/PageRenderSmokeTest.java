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
// has the three placeholder dashboards; each module adds its own rows to pages() below as
// it replaces its placeholder with the real page.
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
                new Page("/seeker/dashboard", "priya@demo.local", "Job Seeker Dashboard"));
    }

    @ParameterizedTest(name = "GET {0} renders for {1}")
    @MethodSource("pages")
    void pageRendersWithItsHeading(Page page) throws Exception {
        UserDetails principal = userDetailsService.loadUserByUsername(page.seederEmail());

        mockMvc.perform(get(page.url()).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(page.expectedHeading())));
    }
}
