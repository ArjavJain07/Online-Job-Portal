package com.jobportal.web.admin;

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

// The real admin dashboard (Section 6.2 DASH-A). This replaces the M1 placeholder, which is
// why M3 (not M1) is where PageRenderSmokeTest's "/admin/dashboard" row first has to match
// real content instead of a stub heading.
class AdminDashboardTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    // AC-DA-1: Total users 10 ("1 admin, 3 employers, 6 seekers"), Live jobs 6, Pending
    // approvals 2; the pending table lists Sales Intern before DevOps Engineer (oldest
    // submitted first, Section 13.4).
    @Test
    void kpisMatchSeedData() throws Exception {
        UserDetails admin = userDetailsService.loadUserByUsername("admin@jobportal.local");

        String body = mockMvc.perform(get("/admin/dashboard").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertKpi(body, "10", "Total users");
        assertKpi(body, "6", "Live jobs");
        assertKpi(body, "2", "Pending approvals");
        assertThat(body).contains("1 admin", "3 employers", "6 seekers");

        int salesIndex = body.indexOf("Sales Intern");
        int devOpsIndex = body.indexOf("DevOps Engineer");
        assertThat(salesIndex).isPositive();
        assertThat(devOpsIndex).isPositive();
        assertThat(salesIndex).isLessThan(devOpsIndex);
    }

    // fragments/kpi-card always renders "<span class="value">N</span>" immediately
    // followed by "<span class="label">Label</span>" (Section 7.1), so matching the pair
    // together is a reliable way to check one specific card's number without depending on
    // exact whitespace.
    private void assertKpi(String html, String value, String label) {
        Pattern pattern = Pattern.compile(
                "class=\"value\">" + Pattern.quote(value) + "</span>\\s*<span class=\"label\">" + Pattern.quote(label)
                        + "</span>");
        assertThat(pattern.matcher(html).find())
                .as("expected KPI card '%s' to show %s", label, value)
                .isTrue();
    }
}
