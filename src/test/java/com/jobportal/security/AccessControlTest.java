package com.jobportal.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    // M6/M7 zones (Section 6.5.1, 6.3 E-D5): a job seeker must not reach the employer's
    // messaging or statistics pages, and an employer must not reach the seeker's own
    // messages - the same "/employer/**"/"/seeker/**" role matchers reject these before
    // dispatch ever finds a handler (Section 4.2), exactly like every other cross-zone case
    // above.
    @Test
    void seekerAndEmployerCannotCrossMessagingOrStatisticsZones() throws Exception {
        UserDetails seeker = userDetailsService.loadUserByUsername("priya@demo.local");
        UserDetails employer = userDetailsService.loadUserByUsername("hr@acme.local");

        mockMvc.perform(get("/employer/messages").with(user(seeker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/employer/messages/new").with(user(seeker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/employer/statistics").with(user(seeker)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/seeker/messages").with(user(employer)))
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
    //
    // Employer ownership (M4, Section 4.5): a job, application or resume id belonging to
    // a DIFFERENT employer is a 404 in both directions - Acme can't reach Globex's, and
    // Globex can't reach Acme's - the same findByIdAndEmployer_Id/
    // findByIdAndJob_Employer_Id rule the plan requires (11.3 contract item 3).
    @Test
    void foreignIdsReturn404() throws Exception {
        mockMvc.perform(get("/jobs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));

        mockMvc.perform(get("/jobs/abc"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Page not found")));

        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        Long globexJobId = data.jobId("Data Analyst");
        Long globexApplicationId = data.applicationId("arjun@demo.local", "Data Analyst");

        mockMvc.perform(get("/employer/jobs/{id}", globexJobId).with(user(acme)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/employer/jobs/{id}/edit", globexJobId).with(user(acme)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/employer/applications/{id}", globexApplicationId).with(user(acme)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/employer/applications/{id}/resume", globexApplicationId).with(user(acme)))
                .andExpect(status().isNotFound());

        UserDetails globex = userDetailsService.loadUserByUsername("talent@globex.local");
        Long acmeJobId = data.jobId("Java Developer");
        Long acmeApplicationId = data.applicationId("priya@demo.local", "Java Developer");

        mockMvc.perform(get("/employer/jobs/{id}", acmeJobId).with(user(globex)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/employer/applications/{id}", acmeApplicationId).with(user(globex)))
                .andExpect(status().isNotFound());

        // AC-P6-2 (M5, Section 6.4): a seeker can only reach their own applications -
        // Priya opening Rohan's A2 (Java Developer) gets 404, the same rule
        // ApplicationTrackingTest#listShowsOnlyOwnActiveApplications also proves.
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long rohansApplicationId = data.applicationId("rohan@demo.local", "Java Developer");

        mockMvc.perform(get("/seeker/applications/{id}", rohansApplicationId).with(user(priya)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/seeker/applications/{id}/resume", rohansApplicationId).with(user(priya)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/seeker/applications/{id}/withdraw", rohansApplicationId).with(user(priya)).with(csrf()))
                .andExpect(status().isNotFound());

        // Messaging threads follow the same application-ownership rule (Section 6.5.1
        // "Ownership": "the same application ownership queries; otherwise 404"): Globex
        // can't open Acme's thread, and Priya can't open Rohan's.
        mockMvc.perform(get("/employer/messages/{id}", acmeApplicationId).with(user(globex)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/seeker/messages/{id}", rohansApplicationId).with(user(priya)))
                .andExpect(status().isNotFound());
    }

    // AC-A-D5-2 (Section 6.2 A-D5, 7.7): the live-feed script always adds
    // X-Requested-With, so an anonymous call WITHOUT it (someone typing the feed URL
    // directly, or a stale bookmark) must be treated like any other anonymous request into
    // an admin URL - a redirect to /login, not the 401 an XHR call gets
    // (ActivityFeedTest#unauthorizedForAnonymousAjax covers that case).
    @Test
    void feedRedirectsAnonymousNonAjax() throws Exception {
        mockMvc.perform(get("/admin/activity/feed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }
}
