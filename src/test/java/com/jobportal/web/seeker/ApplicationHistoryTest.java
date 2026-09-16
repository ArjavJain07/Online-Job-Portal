package com.jobportal.web.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Application history, S-D4 (Section 6.4). Priya has 4 applications total (Section 13.5):
// Java Developer (INTERVIEW, active), Frontend Developer (UNDER_REVIEW, active), Data
// Analyst (APPLIED, active) and Python Backend Developer (REJECTED, final - applied 28
// days ago, rejected 21 days ago).
class ApplicationHistoryTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    // AC-S-D4-1: the default (view=final) history shows only Python Backend Developer,
    // "Not selected", decided 21 days ago (26 Aug 2026, fixed clock 16 Sep 2026), duration
    // 7 days (applied 28 days ago); it does not appear in the S-D2 active list.
    @Test
    void rejectedShownAsNotSelectedInHistoryOnly() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(get("/seeker/applications/history").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Python Backend Developer")))
                .andExpect(content().string(containsString("Not selected")))
                .andExpect(content().string(containsString("26 Aug 2026")))
                .andExpect(content().string(containsString("7 days")))
                .andExpect(content().string(not(containsString("Java Developer"))));

        mockMvc.perform(get("/seeker/applications").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Python Backend Developer"))));
    }

    // AC-S-D4-2: the summary sentence covers all 4 applications regardless of filter;
    // view=all lists every one of them, including the 3 still in progress.
    @Test
    void summaryCountsMatch() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        mockMvc.perform(get("/seeker/applications/history").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "You have applied to 4 jobs: 0 hired, 1 not selected, 0 withdrawn, 3 in progress.")));

        mockMvc.perform(get("/seeker/applications/history").param("view", "all").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Java Developer")))
                .andExpect(content().string(containsString("Frontend Developer")))
                .andExpect(content().string(containsString("Data Analyst")))
                .andExpect(content().string(containsString("Python Backend Developer")));

        // Sneha (Section 13.5): Frontend Developer HIRED and Python Backend Developer
        // WITHDRAWN are her two final applications; Java Developer and Marketing
        // Executive are still active and stay off the default (final) view.
        UserDetails sneha = userDetailsService.loadUserByUsername("sneha@demo.local");
        mockMvc.perform(get("/seeker/applications/history").with(user(sneha)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Frontend Developer")))
                .andExpect(content().string(containsString("Hired")))
                .andExpect(content().string(containsString("Python Backend Developer")))
                .andExpect(content().string(containsString("Withdrawn")));
    }

    // "All applications" (view=all): every application ever made, active ones showing "In
    // progress" in the Decided column (Section 6.4 S-D4 screen).
    @Test
    void allViewListsEverything() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        String body = mockMvc.perform(get("/seeker/applications/history").param("view", "all").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int javaIndex = body.indexOf("Java Developer");
        int frontendIndex = body.indexOf("Frontend Developer");
        int dataIndex = body.indexOf("Data Analyst");
        assertThat(javaIndex).isPositive();
        assertThat(frontendIndex).isPositive();
        assertThat(dataIndex).isPositive();
        // Each of the 3 still-active applications shows "In progress" in its own row.
        long inProgressCount = body.split("In progress", -1).length - 1;
        assertThat(inProgressCount).isGreaterThanOrEqualTo(3);
    }
}
