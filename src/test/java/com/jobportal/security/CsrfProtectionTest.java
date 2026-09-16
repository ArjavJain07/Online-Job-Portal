package com.jobportal.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

// CSRF protection, on by default for every state-changing request (Section 4.7). Section
// 12.2 also asks this test to cover a multipart POST, but the first multipart route
// (resumeFile, on the seeker apply form) does not exist until M5 - that case is added to
// this same method once it does, rather than faked here.
class CsrfProtectionTest extends IntegrationTestBase {

    // A form POST and logout with no CSRF token are both refused with 403, never
    // processed.
    @Test
    void postWithoutTokenIs403() throws Exception {
        mockMvc.perform(post("/register/seeker")
                        .param("fullName", "No Token")
                        .param("email", "notoken@demo.local")
                        .param("password", "Seeker@123")
                        .param("confirmPassword", "Seeker@123"))
                .andExpect(status().isForbidden());

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .param("email", "priya@demo.local")
                        .param("password", "Seeker@123")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());
    }
}
