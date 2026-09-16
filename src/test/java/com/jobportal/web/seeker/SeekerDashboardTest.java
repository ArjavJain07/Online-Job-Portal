package com.jobportal.web.seeker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.support.IntegrationTestBase;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// The real job seeker dashboard (Section 6.4 DASH-S), replacing the M1 placeholder.
class SeekerDashboardTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;

    // AC-DS-1: Priya's dashboard shows Active applications 3, Interviews 1, Hired 0,
    // Unread messages 1 (Section 13.5/13.6), and the first row of "Recent updates" is
    // "Frontend Developer: Under review" (A5, changed 2 days ago - the most recent
    // employer-made status change across every one of her applications).
    @Test
    void kpisAndRecentUpdatesMatchSeedData() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        String body = mockMvc.perform(get("/seeker/dashboard").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertKpi(body, "3", "Active applications");
        assertKpi(body, "1", "Interviews");
        assertKpi(body, "0", "Hired");
        assertKpi(body, "1", "Unread messages");
        assertThat(firstRecentUpdate(body)).isEqualTo("Frontend Developer: Under review");
    }

    // AC-DS-2: the quick search on Priya's dashboard is a GET form to /seeker/jobs with
    // "q" and "location" inputs; searching "java" in "pune" finds exactly the two Live jobs
    // that match both (Java Developer and Spring Boot Intern, Section 13.4).
    @Test
    void quickSearchSubmitsToSeekerJobs() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        String dashboard = mockMvc.perform(get("/seeker/dashboard").with(user(priya)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("method=\"get\"", "action=\"/seeker/jobs\"", "name=\"q\"", "name=\"location\"");

        mockMvc.perform(get("/seeker/jobs").param("q", "java").param("location", "pune").with(user(priya)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 jobs found")));
    }

    // AC-S-D5-1/AC-S-D5-3: as Priya (skills Java, Spring Boot, SQL, Git), both the
    // dashboard card and the full recommendations page name "Spring Boot Intern" with the
    // "Strong match" label and reasons naming Java and Spring Boot; as Neha (empty
    // profile), both show the "Latest jobs" fallback heading and prompt instead.
    @Test
    void recommendationsSectionShowsLabelAndReasons() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");

        for (String url : new String[] {"/seeker/dashboard", "/seeker/recommendations"}) {
            String body = mockMvc.perform(get(url).with(user(priya)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("Spring Boot Intern", "Strong match");
            assertThat(body).containsPattern(Pattern.compile("Matches your skills:[^<]*Java[^<]*Spring Boot"));
        }

        UserDetails neha = userDetailsService.loadUserByUsername("neha@demo.local");
        for (String url : new String[] {"/seeker/dashboard", "/seeker/recommendations"}) {
            String body = mockMvc.perform(get(url).with(user(neha)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("Latest jobs");
            assertThat(body).contains(
                    "Add your skills, location and preferred job type to get personalised recommendations.");
        }
    }

    // The "Recent updates" list renders one <a> per row with the job title and the
    // seeker-facing status label as one text run (seeker/dashboard.html), newest first;
    // this pulls out the first row's text.
    private String firstRecentUpdate(String html) {
        int headerIndex = html.indexOf("Recent updates");
        int rowIndex = html.indexOf("list-group-item", headerIndex);
        int linkStart = html.indexOf("<a ", rowIndex);
        int textStart = html.indexOf('>', linkStart) + 1;
        int textEnd = html.indexOf("</a>", textStart);
        return html.substring(textStart, textEnd).trim();
    }

    // Same regex helper as AdminDashboardTest#assertKpi.
    private void assertKpi(String html, String value, String label) {
        Pattern pattern = Pattern.compile(
                "class=\"value\">" + Pattern.quote(value) + "</span>\\s*<span class=\"label\">" + Pattern.quote(label)
                        + "</span>");
        assertThat(pattern.matcher(html).find())
                .as("expected KPI card '%s' to show %s", label, value)
                .isTrue();
    }
}
