package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobAlertSubscription;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.dto.RecommendedJob;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.repository.JobAlertSubscriptionRepository;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Unit tests for JobAlertService, built by hand with every collaborator mocked (Section
// 12.1) - the same shape PasswordResetServiceTest/NotificationServiceTest already use for a
// similarly token-and-timing-shaped service, and for the same reason: RecommendationService
// is a real, non-trivial scoring engine this class must call but never re-implement (the
// task's central instruction), so a mock lets every test hand it an exact,
// already-decided RecommendationResult instead of fighting seed data or throwaway jobs to
// engineer one. The end-to-end half - opt in over HTTP, a real digest built from real seed
// data, the anonymous unsubscribe link actually working - is JobAlertUnsubscribeFlowTest
// and SavedJobFlowTest's sibling, JobAlertFlowTest, instead.
class JobAlertServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW_INSTANT = Instant.parse("2026-09-16T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final int DIGEST_FREQUENCY_DAYS = 7;
    private static final String SITE_BASE_URL = "https://jobportal.example";

    private JobAlertSubscriptionRepository jobAlertSubscriptionRepository;
    private UserRepository userRepository;
    private RecommendationService recommendationService;
    private NotificationService notificationService;
    private JobAlertService service;

    @BeforeEach
    void setUp() {
        jobAlertSubscriptionRepository = mock(JobAlertSubscriptionRepository.class);
        userRepository = mock(UserRepository.class);
        recommendationService = mock(RecommendationService.class);
        notificationService = mock(NotificationService.class);
        service = new JobAlertService(jobAlertSubscriptionRepository, userRepository, recommendationService,
                notificationService, CLOCK, DIGEST_FREQUENCY_DAYS, SITE_BASE_URL);
    }

    private User seeker(long id, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setEmail("priya@demo.local");
        user.setFullName("Priya Sharma");
        user.setRole(Role.JOB_SEEKER);
        user.setEnabled(enabled);
        return user;
    }

    private Job job(String title, String company, LocalDateTime approvedAt) {
        User employer = new User();
        employer.setCompanyName(company);
        Job job = new Job();
        job.setTitle(title);
        job.setEmployer(employer);
        job.setLocation("Pune");
        job.setApprovedAt(approvedAt);
        return job;
    }

    private JobAlertSubscription subscription(User user, boolean enabled, LocalDateTime lastSentAt) {
        JobAlertSubscription subscription = new JobAlertSubscription();
        subscription.setId(1L);
        subscription.setUser(user);
        subscription.setEnabled(enabled);
        subscription.setUnsubscribeToken("existing-token-value-12345678901234567890");
        subscription.setLastSentAt(lastSentAt);
        return subscription;
    }

    // ---- subscribe / unsubscribeSelf / status ----

    @Test
    void subscribeCreatesANewRowWithAFreshTokenTheFirstTime() {
        User priya = seeker(42L, true);
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.of(priya));

        service.subscribe(42L);

        ArgumentCaptor<JobAlertSubscription> captor = ArgumentCaptor.forClass(JobAlertSubscription.class);
        verify(jobAlertSubscriptionRepository).save(captor.capture());
        JobAlertSubscription saved = captor.getValue();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getUser()).isSameAs(priya);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        // Same entropy/encoding as PasswordResetService's own token (32 random bytes,
        // base64url without padding = 43 characters of [A-Za-z0-9_-]) - see
        // JobAlertSubscription's class comment for why this one is never hashed.
        assertThat(saved.getUnsubscribeToken()).hasSize(43).matches(Pattern.compile("^[A-Za-z0-9_-]+$"));
    }

    @Test
    void subscribeReenablesAndKeepsTheSameTokenWhenARowAlreadyExists() {
        User priya = seeker(42L, true);
        JobAlertSubscription existing = subscription(priya, false, NOW.minusDays(30));
        String originalToken = existing.getUnsubscribeToken();
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.of(existing));

        service.subscribe(42L);

        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getUnsubscribeToken()).isEqualTo(originalToken);
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(jobAlertSubscriptionRepository).save(existing);
        verifyNoInteractions(userRepository);
    }

    @Test
    void unsubscribeSelfDisablesAnExistingRow() {
        User priya = seeker(42L, true);
        JobAlertSubscription existing = subscription(priya, true, null);
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.of(existing));

        service.unsubscribeSelf(42L);

        assertThat(existing.isEnabled()).isFalse();
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(jobAlertSubscriptionRepository).save(existing);
    }

    @Test
    void unsubscribeSelfIsANoOpWhenNeverSubscribed() {
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.empty());

        service.unsubscribeSelf(42L);

        verify(jobAlertSubscriptionRepository, never()).save(any());
    }

    @Test
    void statusReflectsTheStoredRowOrDefaultsToOff() {
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.empty());
        assertThat(service.status(42L)).isEqualTo(new JobAlertService.JobAlertStatus(false, null));

        User priya = seeker(42L, true);
        JobAlertSubscription existing = subscription(priya, true, NOW.minusDays(3));
        when(jobAlertSubscriptionRepository.findByUser_Id(42L)).thenReturn(Optional.of(existing));
        assertThat(service.status(42L)).isEqualTo(new JobAlertService.JobAlertStatus(true, NOW.minusDays(3)));
    }

    // ---- isValidUnsubscribeToken / unsubscribeByToken ----

    @Test
    void isValidUnsubscribeTokenRejectsBlankOrNullWithoutTouchingTheRepository() {
        assertThat(service.isValidUnsubscribeToken(null)).isFalse();
        assertThat(service.isValidUnsubscribeToken("")).isFalse();
        assertThat(service.isValidUnsubscribeToken("   ")).isFalse();
        verifyNoInteractions(jobAlertSubscriptionRepository);
    }

    @Test
    void isValidUnsubscribeTokenIsFalseWhenNoRowMatches() {
        when(jobAlertSubscriptionRepository.findByUnsubscribeToken(anyString())).thenReturn(Optional.empty());
        assertThat(service.isValidUnsubscribeToken("some-token-nobody-has")).isFalse();
    }

    @Test
    void unsubscribeByTokenDisablesTheMatchingRow() {
        User priya = seeker(42L, true);
        JobAlertSubscription existing = subscription(priya, true, null);
        when(jobAlertSubscriptionRepository.findByUnsubscribeToken("the-token")).thenReturn(Optional.of(existing));

        service.unsubscribeByToken("the-token");

        assertThat(existing.isEnabled()).isFalse();
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(jobAlertSubscriptionRepository).save(existing);
    }

    @Test
    void unsubscribeByTokenRejectsAnUnknownToken() {
        when(jobAlertSubscriptionRepository.findByUnsubscribeToken("bogus")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unsubscribeByToken("bogus"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(JobAlertService.INVALID_TOKEN_MESSAGE);
    }

    // ---- sendDueDigests ----

    @Test
    void firstDigestIncludesEveryCurrentMatchWhateverItsAge() {
        User priya = seeker(42L, true);
        JobAlertSubscription due = subscription(priya, true, null); // never sent - always due
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(due));

        RecommendedJob veryOld = new RecommendedJob(job("Spring Boot Intern", "Acme", NOW.minusDays(60)), 34,
                "Strong match", List.of("Matches your skills: Java, Spring Boot"));
        when(recommendationService.recommend(42L, 20)).thenReturn(new RecommendationResult(List.of(veryOld), false));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(1, 0));
        ArgumentCaptor<List<String>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).notifyJobAlertDigest(eq("priya@demo.local"), eq("Priya Sharma"),
                linesCaptor.capture(), eq(SITE_BASE_URL + "/job-alerts/unsubscribe?token=" + due.getUnsubscribeToken()));
        assertThat(linesCaptor.getValue()).hasSize(1);
        assertThat(linesCaptor.getValue().get(0)).contains("Spring Boot Intern").contains("Acme")
                .contains("Matches your skills");
        assertThat(due.getLastSentAt()).isEqualTo(NOW);
        verify(jobAlertSubscriptionRepository).save(due);
    }

    @Test
    void onlyJobsApprovedAfterLastSentAtCountAsNew() {
        User priya = seeker(42L, true);
        // Due (>= the 7-day digest-frequency-days) but recent enough that one of the two
        // mocked matches below was approved after it and the other before.
        LocalDateTime lastSentAt = NOW.minusDays(10);
        JobAlertSubscription due = subscription(priya, true, lastSentAt);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(due));

        RecommendedJob freshlyPosted = new RecommendedJob(job("New Job", "Acme", NOW.minusDays(1)), 20, "Good match",
                List.of("reason"));
        RecommendedJob alreadySeen = new RecommendedJob(job("Old Job", "Acme", NOW.minusDays(15)), 15, "Good match",
                List.of("reason"));
        when(recommendationService.recommend(42L, 20))
                .thenReturn(new RecommendationResult(List.of(freshlyPosted, alreadySeen), false));

        service.sendDueDigests();

        ArgumentCaptor<List<String>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).notifyJobAlertDigest(anyString(), anyString(), linesCaptor.capture(), anyString());
        assertThat(linesCaptor.getValue()).hasSize(1);
        assertThat(linesCaptor.getValue().get(0)).contains("New Job");
    }

    @Test
    void nothingNewIsSkippedSilentlyAndLastSentAtIsLeftUntouched() {
        User priya = seeker(42L, true);
        LocalDateTime lastSentAt = NOW.minusDays(8); // due (>= 7 days), but nothing new since
        JobAlertSubscription due = subscription(priya, true, lastSentAt);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(due));

        RecommendedJob nothingNew = new RecommendedJob(job("Old Job", "Acme", NOW.minusDays(9)), 15, "Good match",
                List.of("reason"));
        when(recommendationService.recommend(42L, 20)).thenReturn(new RecommendationResult(List.of(nothingNew), false));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 1));
        verifyNoInteractions(notificationService);
        assertThat(due.getLastSentAt()).isEqualTo(lastSentAt); // unchanged - stays "due" next tick
        verify(jobAlertSubscriptionRepository, never()).save(any());
    }

    @Test
    void notYetDueSubscriptionsAreSkippedWithoutEverCallingRecommendationService() {
        User priya = seeker(42L, true);
        JobAlertSubscription notDue = subscription(priya, true, NOW.minusDays(2)); // well inside 7 days
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(notDue));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 0));
        verifyNoInteractions(recommendationService, notificationService);
    }

    @Test
    void fallbackRecommendationsNeverProduceADigest() {
        User neha = seeker(99L, true);
        JobAlertSubscription due = subscription(neha, true, null);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(due));
        when(recommendationService.recommend(99L, 20)).thenReturn(new RecommendationResult(List.of(), true));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 1));
        verifyNoInteractions(notificationService);
    }

    @Test
    void aRoleChangedAwayFromSeekerIsSkippedWithoutCallingRecommendationService() {
        User noLongerASeeker = seeker(7L, true);
        noLongerASeeker.setRole(Role.EMPLOYER);
        JobAlertSubscription stale = subscription(noLongerASeeker, true, null);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(stale));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 1));
        verifyNoInteractions(recommendationService, notificationService);
    }

    @Test
    void aDeactivatedAccountIsSkippedWithoutCallingRecommendationService() {
        User deactivated = seeker(7L, false);
        JobAlertSubscription stale = subscription(deactivated, true, null);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(stale));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(0, 1));
        verifyNoInteractions(recommendationService, notificationService);
    }

    // One subscriber's unexpected failure (a mocked RuntimeException, standing in for
    // anything unexpected RecommendationService could throw) must not stop any other
    // subscriber's digest - JobAlertService's own class comment explains why this loop
    // wraps each subscription in its own try/catch, unlike JobSweepService.sweep()'s single
    // aggregate-root loop.
    @Test
    void oneFailingSubscriptionDoesNotStopTheOthers() {
        User priya = seeker(42L, true);
        User rohan = seeker(43L, true);
        rohan.setEmail("rohan@demo.local");
        rohan.setFullName("Rohan Das");
        JobAlertSubscription failing = subscription(priya, true, null);
        JobAlertSubscription succeeding = subscription(rohan, true, null);
        when(jobAlertSubscriptionRepository.findByEnabledTrue()).thenReturn(List.of(failing, succeeding));

        when(recommendationService.recommend(42L, 20)).thenThrow(new RuntimeException("boom"));
        RecommendedJob match = new RecommendedJob(job("Job", "Acme", NOW.minusDays(1)), 20, "Good match", List.of());
        when(recommendationService.recommend(43L, 20)).thenReturn(new RecommendationResult(List.of(match), false));

        JobAlertService.DigestResult result = service.sendDueDigests();

        assertThat(result).isEqualTo(new JobAlertService.DigestResult(1, 1));
        verify(notificationService).notifyJobAlertDigest(eq("rohan@demo.local"), anyString(), any(), anyString());
    }
}
