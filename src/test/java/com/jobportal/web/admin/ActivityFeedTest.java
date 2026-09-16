package com.jobportal.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.ActivityDto;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.ActivityLogService;
import com.jobportal.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Real-time activity feed (Section 6.2 A-D5, 7.7). GET /admin/activity/feed?afterId=N is
// AdminActivityFeedController, deliberately outside com.jobportal.web (Section 7.7) - this
// class lives next to the rest of the M3 admin tests instead, since it exercises the same
// A-D5 feature as ActivityFeedTest's other methods.
class ActivityFeedTest extends IntegrationTestBase {

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private ActivityLogService activityLogService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ObjectMapper objectMapper;

    // AC-A-D5-1: feed?afterId={latest id} returns nothing until something new happens;
    // once it does, it comes back ascending by id and only the entries newer than afterId
    // are included. JobApplicationService#apply (which would normally write this event) is
    // an M5 deliverable that does not exist yet, so this logs the exact
    // APPLICATION_SUBMITTED event apply() will write once that route ships (Section 5.7),
    // to prove the feed mechanism itself rather than the M5 apply flow.
    @Test
    void returnsOnlyNewerEntriesAscending() throws Exception {
        UserDetails admin = admin();
        long latestId = maxActivityId();

        String empty = mockMvc.perform(get("/admin/activity/feed").param("afterId", String.valueOf(latestId))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readValue(empty, ActivityDto[].class)).isEmpty();

        User priya = userRepository.findByEmail("priya@demo.local").orElseThrow();
        activityLogService.log(ActivityType.APPLICATION_SUBMITTED, priya,
                "Priya Sharma applied for Spring Boot Intern at Acme Technologies (APP-00099)",
                TargetType.APPLICATION, 999L);
        activityLogService.log(ActivityType.SETTINGS_UPDATED, priya, "A later, unrelated event", null, null);

        String body = mockMvc.perform(get("/admin/activity/feed").param("afterId", String.valueOf(latestId))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ActivityDto[] events = objectMapper.readValue(body, ActivityDto[].class);

        assertThat(events).hasSize(2);
        assertThat(events[0].id()).isLessThan(events[1].id());
        assertThat(events[0].type()).isEqualTo("APPLICATION_SUBMITTED");
        assertThat(events[0].actorName()).isEqualTo("Priya Sharma");
    }

    @Test
    void limitsTo50() throws Exception {
        UserDetails admin = admin();
        long baseline = maxActivityId();
        User actor = userRepository.findByEmail("admin@jobportal.local").orElseThrow();
        for (int i = 0; i < 55; i++) {
            activityLogService.log(ActivityType.SETTINGS_UPDATED, actor, "Feed limit test event " + i, null, null);
        }

        String body = mockMvc.perform(get("/admin/activity/feed").param("afterId", String.valueOf(baseline))
                        .with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ActivityDto[] events = objectMapper.readValue(body, ActivityDto[].class);

        assertThat(events).hasSize(50);
        for (int i = 1; i < events.length; i++) {
            assertThat(events[i].id()).isGreaterThan(events[i - 1].id());
        }
    }

    // AC-A-D5-2 (first clause).
    @Test
    void forbiddenForEmployer() throws Exception {
        UserDetails employer = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/admin/activity/feed").with(user(employer))).andExpect(status().isForbidden());
    }

    // AC-A-D5-2 (second clause): an anonymous AJAX call gets 401, not a redirect (the
    // anonymous-without-the-header case is AccessControlTest#feedRedirectsAnonymousNonAjax).
    @Test
    void unauthorizedForAnonymousAjax() throws Exception {
        mockMvc.perform(get("/admin/activity/feed").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    // AC-A-D5-2 (third clause): the page renders the feed URL and interval from the
    // current settings row, and changing feedRefreshSeconds changes it on the next render
    // (no cache, Section 7.5).
    @Test
    void intervalRenderedFromSettings() throws Exception {
        UserDetails admin = admin();

        String before = mockMvc.perform(get("/admin/activity").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(before).contains("data-feed-url=\"/admin/activity/feed\"").contains("data-interval-ms=\"5000\"");

        mockMvc.perform(post("/admin/settings").with(user(admin)).with(csrf())
                        .param("siteName", "JobPortal")
                        .param("pageSize", "10")
                        .param("maxActiveJobsPerEmployer", "20")
                        .param("maxResumeSizeMb", "2")
                        .param("allowedResumeTypes", "pdf", "doc", "docx")
                        .param("feedRefreshSeconds", "10")
                        .param("seekerRegistrationOpen", "true")
                        .param("employerRegistrationOpen", "true")
                        .param("jobApprovalRequired", "true"))
                .andExpect(status().is3xxRedirection());

        String after = mockMvc.perform(get("/admin/activity").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(after).contains("data-interval-ms=\"10000\"");
    }

    // AC-A-D5-2 (fourth clause): both pages that need "real-time" behaviour render the
    // latest-applications panel and load the polling script (Section 7.7 - easy to forget).
    @Test
    void latestApplicationsPanelRendered() throws Exception {
        UserDetails admin = admin();

        String activityPage = mockMvc.perform(get("/admin/activity").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(activityPage).contains("id=\"latest-applications\"").contains("/js/activity-feed.js");

        String dashboardPage = mockMvc.perform(get("/admin/dashboard").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboardPage).contains("id=\"latest-applications\"").contains("/js/activity-feed.js");
    }

    // Section 5.7: MESSAGE_SENT descriptions name the application, never the body. The
    // seed data already logs the 8 seeded messages (Section 13.7), so this checks every
    // one of them, plus a freshly logged one, never contains an actual message body.
    @Test
    void messageBodiesNeverLogged() {
        List<String> bodySnippets = List.of("Thursday at 11am", "printed copy of your resume",
                "shortlisted for Java Developer", "dashboard you have built", "sales dashboard in Power BI",
                "Welcome aboard, Sneha", "excited to join");

        List<ActivityLog> seededMessageEvents = activityLogRepository.findAll().stream()
                .filter(e -> e.getType() == ActivityType.MESSAGE_SENT)
                .toList();
        assertThat(seededMessageEvents).hasSize(8);
        for (ActivityLog event : seededMessageEvents) {
            // DemoDataLoader logs an employer's message as "... messaged a candidate
            // (ref)" and a seeker's reply as "... replied to ... (ref)" - either way, only
            // names and a reference, never the body (Section 5.7).
            assertThat(event.getDescription()).containsAnyOf("messaged a candidate", "replied to");
            for (String snippet : bodySnippets) {
                assertThat(event.getDescription()).doesNotContain(snippet);
            }
        }

        User acme = userRepository.findByEmail("hr@acme.local").orElseThrow();
        activityLogService.log(ActivityType.MESSAGE_SENT, acme, "Acme Technologies messaged a candidate (APP-00099)",
                TargetType.APPLICATION, 999L);
        ActivityLog fresh = activityLogRepository.findAll().stream()
                .filter(e -> e.getType() == ActivityType.MESSAGE_SENT)
                .reduce((first, second) -> second).orElseThrow();
        assertThat(fresh.getDescription()).doesNotContain("Welcome aboard");
    }

    private long maxActivityId() {
        return activityLogRepository.findAll().stream().mapToLong(ActivityLog::getId).max().orElse(0L);
    }

    private UserDetails admin() {
        return userDetailsService.loadUserByUsername("admin@jobportal.local");
    }
}
