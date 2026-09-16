package com.jobportal.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// URL-zone access rules (Section 4.1, 4.2): every zone is decided by SecurityConfig, not
// by a @PreAuthorize on a controller, so most of this class works even for a zone whose
// controllers do not exist yet - the security filter chain rejects the request before
// dispatch ever finds a (missing) handler. AC-P6-1; the rest of this matrix (the admin
// job/application/thread/resume ownership checks of #foreignIdsReturn404, and
// #feedRedirectsAnonymousNonAjax) grows as each module adds its own routes.
class AccessControlTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    // AC-P6-1 (second clause): every protected zone sends an anonymous, non-AJAX request
    // to /login; so does a completely unknown URL like /nope (Section 4.2, M0 spike 4).
    @Test
    void anonymousRedirectedToLoginForEachZone() throws Exception {
        for (String url : new String[] {"/dashboard", "/admin/dashboard", "/employer/dashboard",
                "/seeker/dashboard", "/nope"}) {
            // LoginUrlAuthenticationEntryPoint builds a full absolute URL (scheme, host
            // and port included), not a context-relative one.
            mockMvc.perform(get(url))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));
        }
    }

    // AC-P6-1 (first clause): an employer opening an admin URL gets 403, whether or not
    // that URL's controller has been built yet - the security zone rejects it first.
    @Test
    void employerGets403OnAdminPages() throws Exception {
        UserDetails employer = userDetailsService.loadUserByUsername("hr@acme.local");

        mockMvc.perform(get("/admin/users").with(user(employer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/dashboard").with(user(employer)))
                .andExpect(status().isForbidden());
    }

    // Same zone rule, the other way round: a job seeker cannot open the employer zone.
    @Test
    void seekerGets403OnEmployerPages() throws Exception {
        UserDetails seeker = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(get("/employer/dashboard").with(user(seeker)))
                .andExpect(status().isForbidden());
    }

    // AC-P6-1 (third clause): a logged-in user gets 404, not a redirect to /login, for an
    // unknown URL (MockMvc sees only the status here, Section 12.1).
    @Test
    void unknownUrlIs404ForLoggedInUser() throws Exception {
        UserDetails seeker = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(get("/nope").with(user(seeker)))
                .andExpect(status().isNotFound());
    }

    // AC-P6-1 (fourth clause): a bad job id and a non-numeric one both give 404, with the
    // "Page not found" text - both come from GlobalExceptionHandler, which renders the
    // page directly (Section 12.1), so the text can be checked here even from MockMvc.
    @Test
    void foreignIdsReturn404() throws Exception {
        mockMvc.perform(get("/jobs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));

        mockMvc.perform(get("/jobs/abc"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));
    }
}
