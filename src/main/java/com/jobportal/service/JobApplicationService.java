package com.jobportal.service;

import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.CandidateProfile;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.projection.ApplicationIdCount;
import com.jobportal.web.form.ApplicationStatusForm;
import com.jobportal.web.form.InternalNoteForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job applications (Section 6.3, 6.4). This M1 slice holds only the shared
// status-history helper every later status-changing method must call through (11.3
// contract item 6); M4 (employer side) and M5 (seeker side) both add methods to this
// class, each in its own marked section below, to avoid merge conflicts (contract item 11).
@Service
public class JobApplicationService {

    // Filter values accepted by the employer applications list (Section 6.3 E-D2, 7.9
    // binding rule): "Any" (ALL, also the fallback for anything unrecognised) and
    // "Active" (the four statuses ApplicationStatus.isActive() calls active) sit
    // alongside every individual status name.
    private static final Set<ApplicationStatus> ACTIVE_STATUSES =
            EnumSet.of(ApplicationStatus.APPLIED, ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED,
                    ApplicationStatus.INTERVIEW);

    private final JobApplicationRepository jobApplicationRepository;
    private final ApplicationStatusChangeRepository applicationStatusChangeRepository;
    private final JobRepository jobRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final MessageRepository messageRepository;
    private final ActivityLogService activityLogService;
    private final SettingsService settingsService;
    private final Clock clock;

    public JobApplicationService(JobApplicationRepository jobApplicationRepository,
            ApplicationStatusChangeRepository applicationStatusChangeRepository, JobRepository jobRepository,
            SeekerProfileRepository seekerProfileRepository, MessageRepository messageRepository,
            ActivityLogService activityLogService, SettingsService settingsService, Clock clock) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.applicationStatusChangeRepository = applicationStatusChangeRepository;
        this.jobRepository = jobRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.messageRepository = messageRepository;
        this.activityLogService = activityLogService;
        this.settingsService = settingsService;
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

    // ==================== M4: employer side (Section 6.3 E-F2, E-D2) ====================

    // ---- List (E-D2) ----

    // jobId and status arrive as raw, optional strings straight from the query string
    // (Section 7.9 binding rule): a foreign or non-numeric jobId is ignored (Section 4.5,
    // "no leak") and an unrecognised status falls back to "ALL". Ordered newest applied
    // first (E-D2).
    public ApplicationsQueue listForEmployer(Long employerId, String rawJobId, String rawStatus, String rawPage) {
        Long jobId = ownJobIdOrNull(employerId, rawJobId);
        String status = normaliseStatus(rawStatus);
        Set<ApplicationStatus> statuses = statusesFor(status);

        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(),
                Sort.by(Sort.Direction.DESC, "appliedAt"));
        Page<JobApplication> applications = jobApplicationRepository.findForEmployer(employerId, jobId, statuses, pageable);

        // The jobId select ("own jobs, including closed") needs every job regardless of
        // status, so it is built straight from JobSpecifications rather than from the
        // statuses already on this page.
        List<Job> ownJobs = jobRepository.findAll(JobSpecifications.hasEmployer(employerId),
                Sort.by(Sort.Direction.ASC, "title"));

        return new ApplicationsQueue(applications, ownJobs, jobId, status, unreadCountsByApplication(employerId));
    }

    // Per-thread unread counts (Section 6.5.1/7.6) for the "Unread messages" column: a
    // message's recipient can only be the job's employer or the applicant (Section 6.5.1
    // D-13), so counting by recipient = this employer already scopes the result to their
    // own applications with no extra filter needed.
    private Map<Long, Long> unreadCountsByApplication(Long employerId) {
        Map<Long, Long> counts = new HashMap<>();
        for (ApplicationIdCount row : messageRepository.countUnreadByApplication(employerId)) {
            counts.put(row.getApplicationId(), row.getTotal());
        }
        return counts;
    }

    // A jobId only ever narrows the list when it names one of the employer's OWN jobs
    // (Section 4.5): blank, non-numeric, unknown or another employer's job id is treated
    // as "no filter" instead of raising an error or, worse, leaking a wrong count.
    private Long ownJobIdOrNull(Long employerId, String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        Long jobId;
        try {
            jobId = Long.valueOf(rawJobId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return jobRepository.findByIdAndEmployer_Id(jobId, employerId).isPresent() ? jobId : null;
    }

    // "ALL" (missing or unrecognised, Section 7.9 binding rule), "ACTIVE", or one
    // ApplicationStatus name.
    private String normaliseStatus(String raw) {
        if (raw == null) {
            return "ALL";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("ALL") || upper.equals("ACTIVE")) {
            return upper;
        }
        try {
            return ApplicationStatus.valueOf(upper).name();
        } catch (IllegalArgumentException e) {
            return "ALL";
        }
    }

    private Set<ApplicationStatus> statusesFor(String status) {
        if ("ACTIVE".equals(status)) {
            return ACTIVE_STATUSES;
        }
        if ("ALL".equals(status)) {
            return EnumSet.allOf(ApplicationStatus.class);
        }
        return EnumSet.of(ApplicationStatus.valueOf(status));
    }

    private int parsePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(raw.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Detail (E-D2, E-F2) ----

    // Ownership (Section 4.5, 11.3 contract item 3): only the employer who owns the
    // job the candidate applied to may load the application.
    public JobApplication getForEmployer(Long applicationId, Long employerId) {
        return jobApplicationRepository.findByIdAndJob_Employer_Id(applicationId, employerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
    }

    // The application's full status history, oldest first (Section 6.3 E-F2 detail page).
    public List<ApplicationStatusChange> timeline(Long applicationId) {
        return applicationStatusChangeRepository.findByApplication_IdOrderByChangedAtAsc(applicationId);
    }

    // Exactly the seeker fields Section 4.5's ownership table allows an employer to see
    // (email, phone, location, headline, skills, experience, education, about) - nothing
    // more, and never the profile resume file (only the application's own copy is ever
    // served to an employer). Kept as a dedicated read model instead of handing the
    // template the seeker's User/SeekerProfile entities directly, so the "only these
    // fields" rule cannot accidentally be loosened by a later template edit.
    public CandidateProfile candidateProfile(JobApplication application) {
        User seeker = application.getSeeker();
        SeekerProfile profile = seekerProfileRepository.findByUser_Id(seeker.getId()).orElse(null);
        return new CandidateProfile(seeker.getFullName(), !seeker.isEnabled(), seeker.getEmail(),
                profile == null ? null : profile.getPhone(), profile == null ? null : profile.getLocation(),
                profile == null ? null : profile.getHeadline(), parseSkills(profile == null ? null : profile.getSkills()),
                profile == null ? 0 : profile.getExperienceYears(), profile == null ? null : profile.getEducation(),
                profile == null ? null : profile.getAbout());
    }

    // Same splitting logic as Job.skillList() (Section 7.1 note: templates never parse
    // this themselves), just for a seeker profile's normalised skills CSV instead of a
    // job's - SeekerProfile carries no such helper of its own (Section 3.2/5.2: entities
    // outside this slice's file ownership are read-only here).
    private List<String> parseSkills(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String skill : csv.split(",")) {
            String trimmed = skill.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ---- Status change (E-F2) ----

    // AC-E-F2-1/AC-E-F2-2: the transition must be one currentStatus.canTransitionTo
    // allows, and WITHDRAWN is refused with its own message regardless of the current
    // status, since only the seeker may withdraw (Section 6.3 E-F2 business rule 2). The
    // acting employer is read straight off the job (ownership already proved it is the
    // same account), so no extra lookup is needed the way JobModerationService needs
    // loadAdmin for an id that arrives separately from the record being changed.
    @Transactional
    public JobApplication changeStatus(Long applicationId, Long employerId, ApplicationStatusForm form) {
        JobApplication application = getForEmployer(applicationId, employerId);
        ApplicationStatus currentStatus = application.getStatus();
        ApplicationStatus newStatus = form.getStatus();

        if (newStatus == ApplicationStatus.WITHDRAWN) {
            throw new BusinessRuleException("Only the candidate can withdraw an application.");
        }
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new BusinessRuleException(
                    "Can't change status from " + currentStatus.getLabel() + " to " + newStatus.getLabel() + ".");
        }

        User employer = application.getJob().getEmployer();
        String description = employer.getCompanyName() + " moved " + application.getReference() + " to "
                + newStatus.getLabel();
        recordStatusChange(application, newStatus, form.getNoteToCandidate(), employer,
                ActivityType.APPLICATION_STATUS_CHANGED, description);
        return application;
    }

    // ---- Internal note (E-F2) ----

    // Section 6.3 E-F2 business rule 6 / 11.3 contract item 5: the private note is never
    // logged (it would otherwise leak into the activity feed every employer and admin
    // can read) and never reaches a seeker page - JobApplication.internalNote is read
    // only by this class's own employer-side methods and rendered only by
    // employer/application-detail.html.
    @Transactional
    public JobApplication saveInternalNote(Long applicationId, Long employerId, InternalNoteForm form) {
        JobApplication application = getForEmployer(applicationId, employerId);
        application.setInternalNote(form.getInternalNote());
        application.setUpdatedAt(LocalDateTime.now(clock));
        jobApplicationRepository.save(application);
        return application;
    }

    // What EmployerApplicationController needs to render employer/applications.html: the
    // current page, every one of the employer's own jobs (for the jobId select, Section
    // 6.3 E-D2: "including closed"), the effective jobId/status filters (so the form
    // keeps whatever was submitted, Section 7.9) and the unread-message count per
    // application (Section 6.5.1).
    public record ApplicationsQueue(Page<JobApplication> applications, List<Job> ownJobs, Long jobId, String status,
            Map<Long, Long> unreadCounts) {
    }

    // M5 adds the seeker-side methods here
}
