package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.ResultActions;

// Employer company profile (Section 6.3 EP). Anita Rao / Acme Technologies (Section
// 13.2) starts with companyWebsite https://acme.example and the company description
// quoted there, both required fields for a valid save.
class EmployerProfileTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private UserRepository userRepository;

    // AC-EP-1: changing the company name shows the flash and the new name appears on
    // every one of the employer's job pages, here checked on Java Developer (J1).
    @Test
    void companyNameUpdateShownOnJobPage() throws Exception {
        UserDetails employer = acme();
        Long jobId = data.jobId("Java Developer");

        mockMvc.perform(post("/employer/profile").with(user(employer)).with(csrf())
                        .param("fullName", "Anita Rao")
                        .param("email", "hr@acme.local")
                        .param("companyName", "Acme Tech Pvt Ltd")
                        .param("companyWebsite", "https://acme.example")
                        .param("companyDescription", "Product engineering company building HR software in Pune."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/profile"))
                .andExpect(flash().attribute("success", "Company profile updated."));

        assertThat(userRepository.findById(data.userId("hr@acme.local")).orElseThrow().getCompanyName())
                .isEqualTo("Acme Tech Pvt Ltd");

        mockMvc.perform(get("/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Acme Tech Pvt Ltd")));
    }

    // Section 6.3 EP output: "Changing the email logs the user out and redirects to
    // /login?emailChanged." (the same pattern AdminUserManagementTest#ownEmailChangeForcesRelogin
    // proves for the admin's own row.)
    @Test
    void emailChangeForcesRelogin() throws Exception {
        UserDetails employer = acme();

        mockMvc.perform(post("/employer/profile").with(user(employer)).with(csrf())
                        .param("fullName", "Anita Rao")
                        .param("email", "anita.new@acme.local")
                        .param("companyName", "Acme Technologies")
                        .param("companyWebsite", "https://acme.example")
                        .param("companyDescription", "Product engineering company building HR software in Pune."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?emailChanged"));

        login("anita.new@acme.local", "Employer@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/employer/dashboard"));
    }

    private UserDetails acme() {
        return userDetailsService.loadUserByUsername("hr@acme.local");
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }
}
