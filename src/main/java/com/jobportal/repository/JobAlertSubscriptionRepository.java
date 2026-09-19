package com.jobportal.repository;

import com.jobportal.domain.JobAlertSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobAlertSubscriptionRepository extends JpaRepository<JobAlertSubscription, Long> {

    // A seeker's own subscription, always loaded by their own user id (Section 4.5) - the
    // seeker/job-alerts settings page and the opt-in/opt-out actions all go through this,
    // never a URL id. Mirrors SeekerProfileRepository.findByUser_Id.
    Optional<JobAlertSubscription> findByUser_Id(Long userId);

    // The public, anonymous unsubscribe link (JobAlertUnsubscribeController): an exact,
    // indexed match on the raw token, the same shape as
    // PasswordResetTokenRepository.findByTokenHash except this one looks up the token
    // itself rather than a hash of it - see JobAlertSubscription's class comment for why.
    Optional<JobAlertSubscription> findByUnsubscribeToken(String unsubscribeToken);

    // JobAlertService.sendDueDigests()'s batch, the same "one query loads every candidate,
    // small at this app's scale" shape JobSweepService.sweep() already uses for every
    // APPROVED job. Employer/company fields are never touched from this list (only user,
    // for the recipient's email/name and role), so no EntityGraph is needed on `user`
    // itself - it is a to-one association fetched lazily but read for every row regardless,
    // which is the ordinary "LAZY plus open-in-view" cost every other to-one association in
    // this project already accepts (5.3 mapping rule).
    @EntityGraph(attributePaths = "user")
    List<JobAlertSubscription> findByEnabledTrue();

    // Dependency cleanup when a USER is deleted (UserService.delete, Section 5.8): no ON
    // DELETE clause on job_alert_subscriptions.user_id (V5 migration's own comment), so
    // this mirrors the existing passwordResetTokenRepository.deleteByUser_Id call it now
    // sits next to, for the identical reason.
    long deleteByUser_Id(Long userId);
}
