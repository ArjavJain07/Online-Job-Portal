package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobAlertSubscription;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.dto.RecommendedJob;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobAlertSubscriptionRepository;
import com.jobportal.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job alerts: a seeker opts in, and a periodic digest emails them the jobs that newly
// match their profile since the last one actually sent (Section 16 future-work item 5).
//
// REUSE, NOT REBUILD (the task's central instruction): matching is entirely
// RecommendationService.recommend(seekerId, limit) - the same scored, explained,
// already-applied-excluded list the seeker's own dashboard and /seeker/recommendations
// page show. This class is NOT a second matching engine; it calls the existing one and
// adds exactly two things recommend() has no reason to know about: (1) "since when" (a
// per-subscription baseline, not a request-time concern) and (2) "say it by email instead
// of rendering it" (NotificationService, follows its own established @Async/plain-Strings
// pattern - see notifyJobAlertDigest on that class). RecommendationScorer/
// RecommendationService themselves are never touched.
//
// WHO IS "DUE": digestFrequencyDays (default 7, app.job-alerts.digest-frequency-days) is
// the promise made to the seeker ("weekly digest") - independent of how often
// JobAlertScheduler happens to tick (app.job-alerts.check-interval-ms, a much smaller
// number, mirroring JobSweepScheduler's own tick). Ticking often but only ACTING once
// digestFrequencyDays has elapsed since the last real send is what lets a subscriber with
// nothing new this week get picked up again the very next tick instead of waiting out a
// fixed week once something finally does match - see sendOneDigest below for where that
// distinction actually matters.
@Service
public class JobAlertService {

    private static final Logger log = LoggerFactory.getLogger(JobAlertService.class);

    public static final String INVALID_TOKEN_MESSAGE = "This unsubscribe link is invalid.";

    // Same entropy and encoding as PasswordResetService.newRawToken() (32 bytes = 256 bits,
    // Base64 URL-safe without padding = 43 characters of [A-Za-z0-9_-], already URL-safe
    // with no percent-encoding needed) - see JobAlertSubscription's class comment for why
    // this token is stored raw rather than hashed like a password-reset token, which is the
    // ONLY reason this class cannot simply call PasswordResetService and instead repeats
    // its small token-generation recipe.
    private static final int TOKEN_BYTES = 32;

    // Section 7.8 step 5's own "top 20" pool (the same limit SeekerJobController uses for
    // the full /seeker/recommendations page): generous enough that a week's worth of newly
    // Live matches is very unlikely to be cut off by RecommendationService's own ranking,
    // without pulling its full 200-candidate pool into every digest.
    private static final int DIGEST_CANDIDATE_LIMIT = 20;

    private final JobAlertSubscriptionRepository jobAlertSubscriptionRepository;
    private final UserRepository userRepository;
    private final RecommendationService recommendationService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final int digestFrequencyDays;
    private final String siteBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public JobAlertService(JobAlertSubscriptionRepository jobAlertSubscriptionRepository, UserRepository userRepository,
            RecommendationService recommendationService, NotificationService notificationService, Clock clock,
            @Value("${app.job-alerts.digest-frequency-days:7}") int digestFrequencyDays,
            @Value("${app.site-base-url}") String siteBaseUrl) {
        this.jobAlertSubscriptionRepository = jobAlertSubscriptionRepository;
        this.userRepository = userRepository;
        this.recommendationService = recommendationService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.digestFrequencyDays = digestFrequencyDays;
        this.siteBaseUrl = siteBaseUrl;
    }

    // ---- Seeker-facing settings (GET/POST /seeker/job-alerts) ----

    @Transactional(readOnly = true)
    public JobAlertStatus status(Long seekerId) {
        return jobAlertSubscriptionRepository.findByUser_Id(seekerId)
                .map(s -> new JobAlertStatus(s.isEnabled(), s.getLastSentAt()))
                .orElseGet(() -> new JobAlertStatus(false, null));
    }

    // Opts a seeker in. Creates the row (and its one, permanent unsubscribe token) the
    // first time; re-enabling later reuses the SAME row and token rather than issuing a new
    // one - see JobAlertSubscription's class comment for why that reuse is deliberate.
    @Transactional
    public void subscribe(Long seekerId) {
        LocalDateTime now = LocalDateTime.now(clock);
        JobAlertSubscription subscription = jobAlertSubscriptionRepository.findByUser_Id(seekerId).orElse(null);
        if (subscription == null) {
            User user = userRepository.findById(seekerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Seeker " + seekerId + " does not exist"));
            subscription = new JobAlertSubscription();
            subscription.setUser(user);
            subscription.setUnsubscribeToken(newToken());
            subscription.setCreatedAt(now);
        }
        subscription.setEnabled(true);
        subscription.setUpdatedAt(now);
        jobAlertSubscriptionRepository.save(subscription);
    }

    // Opts a seeker out from their own, logged-in settings page. A seeker who was never
    // subscribed has nothing to turn off - a harmless no-op, not an error.
    @Transactional
    public void unsubscribeSelf(Long seekerId) {
        jobAlertSubscriptionRepository.findByUser_Id(seekerId).ifPresent(subscription -> {
            subscription.setEnabled(false);
            subscription.setUpdatedAt(LocalDateTime.now(clock));
            jobAlertSubscriptionRepository.save(subscription);
        });
    }

    // ---- Public, anonymous unsubscribe link (I-17-style tokenised link, Section 16) ----

    // GET preview, the same read-only "does this link even exist" split
    // PasswordResetService.isValidToken() uses - never itself flips `enabled`, so a mail
    // client that pre-fetches links (a known real-world hazard for GET-based unsubscribe
    // links) cannot silently unsubscribe someone before they even open the email. Unlike a
    // reset token this one has no expiry or single-use state to check: it stays "valid" for
    // as long as the row exists, so this is simply an existence check.
    @Transactional(readOnly = true)
    public boolean isValidUnsubscribeToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return jobAlertSubscriptionRepository.findByUnsubscribeToken(rawToken).isPresent();
    }

    // POST, the actual write - re-checks the token itself rather than trusting the
    // controller's earlier isValidToken() check, the same defence-in-depth
    // PasswordResetService.resetPassword() applies to its own token. Idempotent: clicking an
    // old digest's unsubscribe link a second time (or twice in two tabs) just confirms
    // `enabled = false` again.
    @Transactional
    public void unsubscribeByToken(String rawToken) {
        JobAlertSubscription subscription = jobAlertSubscriptionRepository.findByUnsubscribeToken(rawToken)
                .orElseThrow(() -> new BusinessRuleException(INVALID_TOKEN_MESSAGE));
        subscription.setEnabled(false);
        subscription.setUpdatedAt(LocalDateTime.now(clock));
        jobAlertSubscriptionRepository.save(subscription);
    }

    // ---- The digest itself (JobAlertScheduler is the only production caller) ----

    // Every enabled subscription that is due, one at a time - the same "safe to call as
    // often as you like" idempotent shape JobSweepService.sweep() uses for its own batch,
    // and for the same reason: nothing here breaks if two ticks overlap or a run is
    // repeated, so JobAlertServiceTest calls this directly with the fixed test Clock
    // instead of waiting on JobAlertScheduler's timer (Section 12.1).
    @Transactional
    public DigestResult sendDueDigests() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime dueThreshold = now.minusDays(digestFrequencyDays);

        int sent = 0;
        int skipped = 0;
        for (JobAlertSubscription subscription : jobAlertSubscriptionRepository.findByEnabledTrue()) {
            LocalDateTime lastSentAt = subscription.getLastSentAt();
            boolean due = lastSentAt == null || !lastSentAt.isAfter(dueThreshold);
            if (!due) {
                continue;
            }
            try {
                if (sendOneDigest(subscription, now)) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // One seeker's unexpected failure must never stop every OTHER seeker's
                // digest - mirrors JobSweepService.sweep()'s own per-job independence, just
                // made explicit here with a try/catch because this loop reaches into
                // another service (RecommendationService) rather than only ever touching
                // the one aggregate root JobSweepService's loop does.
                log.warn("Skipping job alert digest for subscription {} (user {}): {}",
                        subscription.getId(), subscription.getUser().getId(), e.getMessage());
                skipped++;
            }
        }
        if (sent > 0 || skipped > 0) {
            log.info("Job alert digest run: {} sent, {} skipped (due but nothing new to report)", sent, skipped);
        }
        return new DigestResult(sent, skipped);
    }

    // Returns true when a digest was actually emailed. Every other outcome (no skills/
    // applications yet, nothing new since the baseline, account no longer an enabled
    // seeker) is a deliberate, silent skip - see the inline comments for which rule is
    // protecting against what.
    private boolean sendOneDigest(JobAlertSubscription subscription, LocalDateTime now) {
        User user = subscription.getUser();
        // A role change away from JOB_SEEKER, or a deactivated account, leaves a still-
        // "enabled" subscription row behind: neither UserService's role-change path nor its
        // deactivate path has any reason to know about this table (Section 5.8 lists
        // neither as a dependency of either action, and rightly so - this row is not
        // precious data, just a stale preference). Catching it here, at send time, is
        // simpler and more robust than trying to keep every other place a role or status
        // can change in step with this one.
        if (user.getRole() != Role.JOB_SEEKER || !user.isEnabled()) {
            return false;
        }

        RecommendationResult result = recommendationService.recommend(user.getId(), DIGEST_CANDIDATE_LIMIT);
        if (result.fallback()) {
            // Section 7.8 step 2's fallback ("Latest jobs") is right for a page the seeker
            // is actively looking at, but wrong for an unsolicited email: it is not
            // personalised, carries no "why", and - because the fallback has no per-run
            // "new since X" concept at all - would look identical every single time this
            // runs. That is exactly the "same email every week" pattern the task warns
            // trains people to hit spam. Skip until the profile or an application gives the
            // real algorithm something to score.
            return false;
        }

        LocalDateTime baseline = subscription.getLastSentAt();
        List<String> matchLines = new ArrayList<>();
        for (RecommendedJob recommended : result.jobs()) {
            Job job = recommended.job();
            // First-ever digest (baseline null): every current match counts as "new" - the
            // whole point of opting in is seeing what already fits today, not waiting a
            // week for something to be posted after the click. Every digest after that:
            // only a job that went LIVE since the last digest that actually SENT counts, so
            // the same job is never reported twice and a quiet week produces no line - and
            // therefore no email - at all.
            if (baseline != null && (job.getApprovedAt() == null || !job.getApprovedAt().isAfter(baseline))) {
                continue;
            }
            matchLines.add(describeMatch(job, recommended));
        }

        if (matchLines.isEmpty()) {
            // Nothing new to report. lastSentAt is DELIBERATELY left untouched (see that
            // field's own comment on JobAlertSubscription) rather than stamped "checked, //
            // found nothing": stamping it would make this subscription wait out a full
            // digestFrequencyDays window even if a great match appears an hour later,
            // whereas leaving it alone keeps the subscription "due" on every future tick
            // until there really is something worth emailing - never an empty digest
            // either way, just a difference in how soon a real one can arrive.
            return false;
        }

        String unsubscribeLink = siteBaseUrl + "/job-alerts/unsubscribe?token=" + subscription.getUnsubscribeToken();
        notificationService.notifyJobAlertDigest(user.getEmail(), user.getFullName(), List.copyOf(matchLines),
                unsubscribeLink);

        subscription.setLastSentAt(now);
        jobAlertSubscriptionRepository.save(subscription);
        return true;
    }

    // One line per matched job for the email body (NotificationService takes only
    // already-resolved Strings, never entities - see that class's own comment on why): the
    // title, employer and location a job card would show, plus the same "why recommended"
    // reasons RecommendationScorer already built and the dashboard/recommendations pages
    // already render, so the emailed explanation is never a second, different description
    // of the same score.
    private String describeMatch(Job job, RecommendedJob recommended) {
        StringBuilder line = new StringBuilder(job.getTitle())
                .append(" at ").append(job.getEmployer().getCompanyName())
                .append(" (").append(job.getLocation()).append(")");
        if (!recommended.reasons().isEmpty()) {
            line.append(" - ").append(String.join(", ", recommended.reasons()));
        }
        return line.toString();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // seeker/job-alerts.html's whole model: whether alerts are on, and when the last digest
    // actually went out (null both for "never subscribed" and "subscribed but no digest has
    // gone out yet" - the template shows one "not sent yet" line for both, since the seeker
    // has no reason to care which).
    public record JobAlertStatus(boolean enabled, LocalDateTime lastSentAt) {
    }

    // How many subscriptions this run actually emailed vs. considered-but-skipped (no new
    // matches, fallback-only profile, or a stale/ineligible account) - the same shape as
    // JobSweepService.SweepResult, for JobAlertServiceTest and the log line above.
    public record DigestResult(int sent, int skipped) {
    }
}
