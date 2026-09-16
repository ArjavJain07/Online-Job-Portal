package com.jobportal.web.common;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

// Change password, P-5 (Section 6.1). AC-P5-1.
class AccountTest extends IntegrationTestBase {

    // AC-P5-1: a wrong current password shows the error and changes nothing; the correct
    // change works and the user stays logged in; logging out and back in only the new
    // password works.
    @Test
    void changePasswordRequiresCurrentAndWorks() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login("arjun@demo.local", "Seeker@123", session).andExpect(status().is3xxRedirection());

        changePassword(session, "WrongPassword1", "NewPass@123")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Current password is incorrect.")));

        changePassword(session, "Seeker@123", "NewPass@123")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(flash().attribute("success", "Password changed successfully."));

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        login("arjun@demo.local", "Seeker@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        login("arjun@demo.local", "NewPass@123", new MockHttpSession())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seeker/dashboard"));
    }

    private ResultActions changePassword(MockHttpSession session, String currentPassword, String newPassword)
            throws Exception {
        return mockMvc.perform(post("/account/password")
                .session(session)
                .param("currentPassword", currentPassword)
                .param("newPassword", newPassword)
                .param("confirmPassword", newPassword)
                .with(csrf()));
    }

    private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .session(session)
                .with(csrf()));
    }
}
