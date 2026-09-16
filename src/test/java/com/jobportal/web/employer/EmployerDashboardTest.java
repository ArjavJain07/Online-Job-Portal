package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// The real employer dashboard (Section 6.3 DASH-E), replacing the M1 placeholder.
class EmployerDashboardTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    // AC-DE-1: Acme's dashboard shows Live jobs 4, Pending approval 1, New applications 1,
    // Unread messages 1, and "Needs attention" lists "Python Backend Developer: Expired"
    // (J11, Section 13.4 - approved but its deadline has already passed).
    @Test
    void kpisAndAttentionListMatchSeedData() throws Exception {
        UserDetails employer = userDetailsService.loadUserByUsername("hr@acme.local");

        String body = mockMvc.perform(get("/employer/dashboard").with(user(employer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertKpi(body, "4", "Live jobs");
        assertKpi(body, "1", "Pending approval");
        assertKpi(body, "1", "New applications");
        assertKpi(body, "1", "Unread messages");
        assertThat(body).contains("Python Backend Developer: Expired");
    }

    // Same regex helper as AdminDashboardTest#assertKpi: fragments/kpi-card always renders
    // the value span immediately before the label span (Section 7.1).
    private void assertKpi(String html, String value, String label) {
        Pattern pattern = Pattern.compile(
                "class=\"value\">" + Pattern.quote(value) + "</span>\\s*<span class=\"label\">" + Pattern.quote(label)
                        + "</span>");
        assertThat(pattern.matcher(html).find())
                .as("expected KPI card '%s' to show %s", label, value)
                .isTrue();
    }
}
