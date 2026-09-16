package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobStatusChangeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job postings (Section 6.3). This M1 slice holds only the shared status-history helper
// every later status-changing method must call through (11.3 contract item 6); M4 adds
// its own methods below in the marked section.
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobStatusChangeRepository jobStatusChangeRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public JobService(JobRepository jobRepository, JobStatusChangeRepository jobStatusChangeRepository,
            ActivityLogService activityLogService, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobStatusChangeRepository = jobStatusChangeRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    // Shared history helper (11.3 contract item 6, 5.5, 5.8): moves the job to
    // newStatus, writes a JobStatusChange row and the matching activity log entry, all
    // in one transaction. Callers set any other side-effect fields (approvedAt,
    // closedAt, rejectionReason) on the job before calling this. actor is null only for
    // the one system-driven transition in Section 5.5 (a new job auto-approved because
    // jobApprovalRequired is off); every other caller passes the admin or employer who
    // acted, and activityType/description are whatever that specific transition logs
    // (Section 5.7).
    @Transactional
    public void recordStatusChange(Job job, JobStatus newStatus, String reason, User actor,
            ActivityType activityType, String description) {
        JobStatus previousStatus = job.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);

        job.setStatus(newStatus);
        job.setUpdatedAt(now);
        jobRepository.save(job);

        JobStatusChange change = new JobStatusChange();
        change.setJob(job);
        change.setFromStatus(previousStatus);
        change.setToStatus(newStatus);
        change.setReason(reason);
        change.setActorName(actorDisplayName(actor));
        change.setActorRole(actor != null ? actor.getRole() : null);
        change.setChangedAt(now);
        jobStatusChangeRepository.save(change);

        activityLogService.log(activityType, actor, description, TargetType.JOB, job.getId());
    }

    // "fullName (companyName)" for employers, the plain name otherwise, "System" when no
    // one personally made the decision (Section 5.2).
    private String actorDisplayName(User actor) {
        if (actor == null) {
            return "System";
        }
        if (actor.getRole() == Role.EMPLOYER && actor.getCompanyName() != null) {
            return actor.getFullName() + " (" + actor.getCompanyName() + ")";
        }
        return actor.getFullName();
    }

    // M4/M5 add the employer-side / seeker-side methods here
}
