package com.jobportal.service;

import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job applications (Section 6.3, 6.4). This M1 slice holds only the shared
// status-history helper every later status-changing method must call through (11.3
// contract item 6); M4 (employer side) and M5 (seeker side) both add methods to this
// class, each in its own marked section below, to avoid merge conflicts (contract item 11).
@Service
public class JobApplicationService {

    private final JobApplicationRepository jobApplicationRepository;
    private final ApplicationStatusChangeRepository applicationStatusChangeRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public JobApplicationService(JobApplicationRepository jobApplicationRepository,
            ApplicationStatusChangeRepository applicationStatusChangeRepository,
            ActivityLogService activityLogService, Clock clock) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.applicationStatusChangeRepository = applicationStatusChangeRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    // Shared history helper (11.3 contract item 6): moves the application to newStatus,
    // writes an ApplicationStatusChange row, sets statusChangedAt (every status change
    // after APPLIED, both employer changes and seeker withdrawal), sets
    // seekerLastViewedAt to the same instant for a withdrawal so the seeker's own action
    // raises no "Updated" badge, and writes the matching activity log entry - all in one
    // transaction. apply() sets the initial APPLIED status itself, without calling this.
    @Transactional
    public void recordStatusChange(JobApplication application, ApplicationStatus newStatus, String noteToCandidate,
            User actor, ActivityType activityType, String description) {
        ApplicationStatus previousStatus = application.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);

        application.setStatus(newStatus);
        application.setStatusChangedAt(now);
        application.setUpdatedAt(now);
        if (newStatus == ApplicationStatus.WITHDRAWN) {
            application.setSeekerLastViewedAt(now);
        }
        jobApplicationRepository.save(application);

        ApplicationStatusChange change = new ApplicationStatusChange();
        change.setApplication(application);
        change.setFromStatus(previousStatus);
        change.setToStatus(newStatus);
        change.setNoteToCandidate(noteToCandidate);
        change.setActorName(actorDisplayName(actor));
        change.setActorRole(actor.getRole());
        change.setChangedAt(now);
        applicationStatusChangeRepository.save(change);

        activityLogService.log(activityType, actor, description, TargetType.APPLICATION, application.getId());
    }

    // "fullName (companyName)" for employers, the plain name otherwise (Section 5.2).
    // Application status changes always have an actor (employer or seeker); there is no
    // "System" case here the way there is for jobs.
    private String actorDisplayName(User actor) {
        if (actor.getRole() == Role.EMPLOYER && actor.getCompanyName() != null) {
            return actor.getFullName() + " (" + actor.getCompanyName() + ")";
        }
        return actor.getFullName();
    }

    // M4/M5 add the employer-side / seeker-side methods here
}
