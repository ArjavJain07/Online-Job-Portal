package com.jobportal.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import com.jobportal.support.TestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// CSRF protection, on by default for every state-changing request (Section 4.7).
class CsrfProtectionTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

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

    // Section 12.2's multipart case, added now that the first multipart route exists
    // (S-F2's apply form, M5): a resumeFile upload with no CSRF token is refused with 403,
    // never processed, exactly like an ordinary form POST.
    @Test
    void postWithoutTokenIs403ForMultipart() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long jobId = data.jobId("Spring Boot Intern");
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", TestFiles.pdfBytes());

        mockMvc.perform(multipart("/seeker/jobs/{id}/apply", jobId).file(file).with(user(priya))
                        .param("resumeChoice", "UPLOAD"))
                .andExpect(status().isForbidden());
    }
}
