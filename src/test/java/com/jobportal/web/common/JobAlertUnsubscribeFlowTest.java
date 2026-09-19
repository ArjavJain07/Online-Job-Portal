package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.JobAlertSubscription;
import com.jobportal.repository.JobAlertSubscriptionRepository;
import com.jobportal.service.JobAlertService;
import com.jobportal.service.MailMessage;
import com.jobportal.support.IntegrationTestBase;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// End-to-end tests for job alerts (Section 16 future-work item 5): opting in/out while
// logged in, a real digest built against the Section 13 seed data through
// RecommendationService, and the no-login unsubscribe link the digest email carries.
// JobAlertScheduler's own timer is never exercised (app.job-alerts.enabled=false in the
// test profile, mirroring JobSweepScheduler) - every digest here is triggered by calling
// JobAlertService#sendDueDigests() directly, the same split JobSweepServiceTest already
// uses for the sweep.
class JobAlertUnsubscribeFlowTest extends IntegrationTestBase {

    private static final Pattern UNSUBSCRIBE_LINK_PATTERN =
            Pattern.compile("(http://localhost:8080/job-alerts/unsubscribe\\?token=[A-Za-z0-9_-]+)");

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobAlertService jobAlertService;
    @Autowired
    private JobAlertSubscriptionRepository jobAlertSubscriptionRepository;

    // The full journey: opt in, receive a real digest built from real recommendation
    // matches, click its unsubscribe link with nobody logged in, and land back on the
    // settings page - while logged in - showing alerts off again.
    @Test
    void fullOptInDigestAndNoLoginUnsubscribeJourney() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        Long priyaId = data.userId("priya@demo.local");

        mockMvc.perform(get("/seeker/job-alerts").with(user(priya)))
                .andExpect(content().string(containsString("Turn on job alerts")));

        mockMvc.perform(post("/seeker/job-alerts").with(user(priya)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/seeker/job-alerts").with(user(priya)))
                .andExpect(content().string(containsString("Turn off job alerts")))
                .andExpect(content().string(containsString("No digest has been sent yet")));

        // Priya has real, seeded, non-fallback recommendations (RecommendationServiceTest:
        // Spring Boot Intern then QA Engineer) - the same algorithm, called rather than
        // rebuilt, is what makes this a genuine "does the whole feature actually work"
        // check rather than a mocked stand-in for one.
        JobAlertService.DigestResult result = jobAlertService.sendDueDigests();
        assertThat(result.sent()).isEqualTo(1);
        assertThat(mailSent.sent()).hasSize(1);

        MailMessage digest = mailSent.sent().get(0);
        assertThat(digest.to()).isEqualTo("priya@demo.local");
        assertThat(digest.subject()).contains("match");
        assertThat(digest.body()).contains("Spring Boot Intern");

        Matcher matcher = UNSUBSCRIBE_LINK_PATTERN.matcher(digest.body());
        assertThat(matcher.find()).as("digest body should contain an unsubscribe link").isTrue();
        String unsubscribeLink = matcher.group(1);
        String path = unsubscribeLink.substring("http://localhost:8080".length());

        String originalToken = jobAlertSubscriptionRepository.findByUser_Id(priyaId).orElseThrow().getUnsubscribeToken();

        // Nobody is logged in for either of these two requests - the whole point of a
        // tokenised link (PasswordResetToken's own established pattern, applied here).
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unsubscribe from job alerts?")));

        mockMvc.perform(post("/job-alerts/unsubscribe").param("token", tokenFrom(unsubscribeLink)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You're unsubscribed")));

        assertThat(jobAlertSubscriptionRepository.findByUser_Id(priyaId).orElseThrow().isEnabled()).isFalse();
        mockMvc.perform(get("/seeker/job-alerts").with(user(priya)))
                .andExpect(content().string(containsString("Turn on job alerts")));

        // Re-enabling keeps the SAME token (JobAlertSubscription's own documented reason:
        // the link already sitting in every earlier digest must keep working).
        mockMvc.perform(post("/seeker/job-alerts").with(user(priya)).with(csrf()));
        assertThat(jobAlertSubscriptionRepository.findByUser_Id(priyaId).orElseThrow().getUnsubscribeToken())
                .isEqualTo(originalToken);
    }

    // A second digest run in the same instant must not re-send: Priya's subscription was
    // just stamped lastSentAt = now by the test above's run, so a fresh subscriber run
    // immediately afterwards has nothing due yet.
    @Test
    void aSecondRunImmediatelyAfterwardsSendsNothingMore() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        mockMvc.perform(post("/seeker/job-alerts").with(user(priya)).with(csrf()));

        JobAlertService.DigestResult first = jobAlertService.sendDueDigests();
        JobAlertService.DigestResult second = jobAlertService.sendDueDigests();

        assertThat(first.sent()).isEqualTo(1);
        assertThat(second).isEqualTo(new JobAlertService.DigestResult(0, 0));
        assertThat(mailSent.sent()).hasSize(1);
    }

    // A seeker with nothing for the algorithm to work with yet (Neha: no skills, no
    // applications, Section 13.3) gets RecommendationService's "Latest jobs" fallback on
    // her dashboard - but never as an emailed digest (JobAlertService.sendOneDigest's own
    // documented reasoning: unexplained, identical-every-week "latest jobs" is exactly the
    // pattern that trains people to mark mail as spam).
    @Test
    void aFallbackOnlyProfileNeverReceivesADigest() throws Exception {
        UserDetails neha = userDetailsService.loadUserByUsername("neha@demo.local");
        mockMvc.perform(post("/seeker/job-alerts").with(user(neha)).with(csrf()));

        JobAlertService.DigestResult result = jobAlertService.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 1));
        assertThat(mailSent.sent()).isEmpty();
    }

    @Test
    void unknownOrMissingTokenShowsLinkNoLongerValid() throws Exception {
        mockMvc.perform(get("/job-alerts/unsubscribe").param("token", "does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
        mockMvc.perform(get("/job-alerts/unsubscribe"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
        mockMvc.perform(post("/job-alerts/unsubscribe").param("token", "does-not-exist").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Link no longer valid")));
    }

    @Test
    void bothSeekerSettingsRoutesAndThePublicUnsubscribeRouteRequireCsrf() throws Exception {
        UserDetails priya = userDetailsService.loadUserByUsername("priya@demo.local");
        mockMvc.perform(post("/seeker/job-alerts").with(user(priya))).andExpect(status().isForbidden());
        mockMvc.perform(post("/seeker/job-alerts/disable").with(user(priya))).andExpect(status().isForbidden());
        mockMvc.perform(post("/job-alerts/unsubscribe").param("token", "whatever")).andExpect(status().isForbidden());
    }

    // Existing JobAlertSubscription row: disabling and re-enabling from the logged-in
    // settings page, independent of the emailed link, using the row the other tests in
    // this class do not otherwise touch.
    @Test
    void selfServiceDisableFromTheSettingsPageWorksWithoutAToken() throws Exception {
        UserDetails arjun = userDetailsService.loadUserByUsername("arjun@demo.local");
        Long arjunId = data.userId("arjun@demo.local");
        mockMvc.perform(post("/seeker/job-alerts").with(user(arjun)).with(csrf()));
        assertThat(jobAlertSubscriptionRepository.findByUser_Id(arjunId).orElseThrow().isEnabled()).isTrue();

        mockMvc.perform(post("/seeker/job-alerts/disable").with(user(arjun)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        JobAlertSubscription stored = jobAlertSubscriptionRepository.findByUser_Id(arjunId).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
    }

    private String tokenFrom(String unsubscribeLink) {
        return unsubscribeLink.substring(unsubscribeLink.indexOf("token=") + "token=".length());
    }
}
