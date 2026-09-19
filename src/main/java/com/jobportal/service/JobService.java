package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.Skill;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.EmployerJobHistoryRow;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.repository.projection.JobStatusPairCount;
import com.jobportal.util.SkillParser;
import com.jobportal.web.form.JobForm;
import com.jobportal.web.form.ReopenJobForm;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job postings (Section 6.3). This M1 slice holds only the shared status-history helper
// every later status-changing method must call through (11.3 contract item 6); M4 adds
// its own methods below in the marked section.
@Service
public class JobService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    public static final String CLOSED_CANNOT_EDIT_MESSAGE = "Closed jobs can't be edited. Reopen the job first.";
    public static final String DEADLINE_RANGE_MESSAGE = "Deadline must be between today and 180 days from now.";
    public static final String REOPEN_DEADLINE_RANGE_MESSAGE = "The new deadline must be between today and 180 days from now.";
    public static final String ALREADY_CLOSED_MESSAGE = "This job is already closed.";
    public static final String NOT_CLOSED_MESSAGE = "Only closed jobs can be reopened.";

    private final JobRepository jobRepository;
    private final JobStatusChangeRepository jobStatusChangeRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final ActivityLogService activityLogService;
    private final SkillService skillService;
    private final Clock clock;

    public JobService(JobRepository jobRepository, JobStatusChangeRepository jobStatusChangeRepository,
            JobApplicationRepository jobApplicationRepository, UserRepository userRepository,
            SettingsService settingsService, ActivityLogService activityLogService, SkillService skillService,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.jobStatusChangeRepository = jobStatusChangeRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.activityLogService = activityLogService;
        this.skillService = skillService;
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

    // ==================== M4: employer-side methods (Section 6.3 E-F1/E-D1/E-D4) ====================

    // ---- Create (E-F1) ----

    // AC-E-F1-1/AC-E-F1-3: the active-job limit and the deadline range are checked before
    // anything is built or saved; the job itself is only INSERTed once, inside
    // recordStatusChange, so its first JobStatusChange row has fromStatus = null (the
    // "(new)" row of the 5.5 transition table) instead of a spurious self-transition.
    @Transactional
    public Job create(Long employerId, JobForm form) {
        User employer = loadUser(employerId);
        LocalDate today = LocalDate.now(clock);
        long activeCount = jobRepository.count(activeSpec(employerId));
        int limit = settingsService.get().getMaxActiveJobsPerEmployer();
        if (activeCount >= limit) {
            throw new BusinessRuleException(activeLimitMessage(activeCount));
        }
        validateDeadlineRange(form.getApplicationDeadline(), today, DEADLINE_RANGE_MESSAGE);

        Job job = new Job();
        job.setEmployer(employer);
        applyFormFields(job, form);
        job.setCreatedAt(LocalDateTime.now(clock));

        boolean approvalRequired = settingsService.get().isJobApprovalRequired();
        JobStatus initialStatus = approvalRequired ? JobStatus.PENDING_APPROVAL : JobStatus.APPROVED;
        User actor = approvalRequired ? employer : null; // System auto-approves when approval is off (5.5)
        if (!approvalRequired) {
            job.setApprovedAt(LocalDateTime.now(clock));
        }

        String description = employer.getCompanyName() + " posted " + job.getTitle();
        recordStatusChange(job, initialStatus, null, actor, ActivityType.JOB_POSTED, description);
        return job;
    }

    // Companion to create(), matching the 7.2 controller-pattern example exactly
    // ("redirect.addFlashAttribute("success", jobService.createdMessage(job))").
    public String createdMessage(Job job) {
        return job.getStatus() == JobStatus.PENDING_APPROVAL
                ? "Job '" + job.getTitle() + "' submitted for approval. You'll see the decision under My Jobs."
                : "Job '" + job.getTitle() + "' is now live.";
    }

    // ---- Edit / update (E-D1, 5.5) ----

    // AC-E-D1-2: a content change on a Live job sends it back to PENDING_APPROVAL (unless
    // approval is off); changing only the deadline or openings never does, even on an
    // expired job (its display status recomputes to Live the moment the new deadline is
    // saved - Section 5.5, no separate "reopen" needed). A REJECTED job always resubmits
    // to PENDING_APPROVAL, whatever the setting says (5.5 rule).
    @Transactional
    public JobActionResult update(Long employerId, Long jobId, JobForm form) {
        Job job = getOwned(employerId, jobId);
        if (job.getStatus() == JobStatus.CLOSED) {
            throw new BusinessRuleException(CLOSED_CANNOT_EDIT_MESSAGE);
        }
        LocalDate today = LocalDate.now(clock);
        boolean deadlineChanged = !form.getApplicationDeadline().equals(job.getApplicationDeadline());
        if (deadlineChanged) {
            validateDeadlineRange(form.getApplicationDeadline(), today, DEADLINE_RANGE_MESSAGE);
        }
        boolean contentChanged = contentFieldsChanged(job, form);
        JobStatus previousStatus = job.getStatus();
        User employer = job.getEmployer();
        applyFormFields(job, form);

        if (previousStatus == JobStatus.REJECTED) {
            String description = employer.getCompanyName() + " edited " + job.getTitle() + " (resubmitted)";
            recordStatusChange(job, JobStatus.PENDING_APPROVAL, null, employer, ActivityType.JOB_UPDATED, description);
            return new JobActionResult(job, "Job '" + job.getTitle() + "' resubmitted for approval.");
        }
        if (previousStatus == JobStatus.PENDING_APPROVAL) {
            plainUpdate(job, employer);
            return new JobActionResult(job, "Job '" + job.getTitle() + "' updated.");
        }

        // previousStatus == APPROVED
        boolean approvalRequired = settingsService.get().isJobApprovalRequired();
        if (contentChanged && approvalRequired) {
            String description = employer.getCompanyName() + " edited " + job.getTitle() + " (sent for re-approval)";
            recordStatusChange(job, JobStatus.PENDING_APPROVAL, null, employer, ActivityType.JOB_UPDATED, description);
            return new JobActionResult(job,
                    "Job '" + job.getTitle() + "' updated and sent for re-approval. It is hidden from job seekers until approved.");
        }
        plainUpdate(job, employer);
        return new JobActionResult(job, "Job '" + job.getTitle() + "' updated.");
    }

    // An edit that keeps the job's current status (5.5: "activity JOB_UPDATED, no status
    // row"): saved directly, never through recordStatusChange, which always writes a
    // timeline row even when fromStatus equals toStatus.
    private void plainUpdate(Job job, User employer) {
        job.setUpdatedAt(LocalDateTime.now(clock));
        jobRepository.save(job);
        String description = employer.getCompanyName() + " edited " + job.getTitle();
        activityLogService.log(ActivityType.JOB_UPDATED, employer, description, TargetType.JOB, job.getId());
    }

    // Section 5.5 "content fields" list: title, description, requirements, skills,
    // category, jobType, workMode, location, salaryMin, salaryMax, minExperienceYears.
    // Deliberately excludes applicationDeadline and openings.
    private boolean contentFieldsChanged(Job job, JobForm form) {
        return !job.getTitle().equals(form.getTitle())
                || !job.getDescription().equals(form.getDescription())
                || !job.getRequirements().equals(form.getRequirements())
                || skillsChanged(job, form)
                || job.getCategory() != form.getCategory()
                || job.getJobType() != form.getJobType()
                || job.getWorkMode() != form.getWorkMode()
                || !job.getLocation().equals(form.getLocation())
                || job.getSalaryMin() != form.getSalaryMin()
                || job.getSalaryMax() != form.getSalaryMax()
                || job.getMinExperienceYears() != form.getMinExperienceYears();
    }

    // Whether the edit really changes which skills the job asks for (business rule 4,
    // Section 5.5). Compares canonical keys in order, so re-typing "java" as "Java", or
    // "Node.js" as "Node JS", is not a content change and does not send a Live job back
    // for re-approval - while adding, removing or reordering a skill is.
    //
    // Deliberately does NOT go through SkillService: this runs before the decision to
    // save anything, and resolving would create rows for skills the employer might be
    // about to correct. SkillParser.keys gives the same keys with no database at all.
    private boolean skillsChanged(Job job, JobForm form) {
        List<String> current = new ArrayList<>();
        for (Skill skill : job.getSkills()) {
            current.add(skill.getSlug());
        }
        return !current.equals(new ArrayList<>(SkillParser.keys(form.getSkills())));
    }

    private void applyFormFields(Job job, JobForm form) {
        job.setTitle(form.getTitle());
        job.setDescription(form.getDescription());
        job.setRequirements(form.getRequirements());
        job.assignSkills(skillService.resolve(form.getSkills()));
        job.setCategory(form.getCategory());
        job.setJobType(form.getJobType());
        job.setWorkMode(form.getWorkMode());
        job.setLocation(form.getLocation());
        job.setSalaryMin(form.getSalaryMin());
        job.setSalaryMax(form.getSalaryMax());
        job.setMinExperienceYears(form.getMinExperienceYears());
        job.setOpenings(form.getOpenings());
        job.setApplicationDeadline(form.getApplicationDeadline());
    }

    // ---- Close / reopen / delete (E-D1) ----

    @Transactional
    public Job close(Long employerId, Long jobId) {
        Job job = getOwned(employerId, jobId);
        if (job.getStatus() == JobStatus.CLOSED) {
            throw new BusinessRuleException(ALREADY_CLOSED_MESSAGE);
        }
        User employer = job.getEmployer();
        job.setClosedAt(LocalDateTime.now(clock));
        String description = employer.getCompanyName() + " closed " + job.getTitle();
        recordStatusChange(job, JobStatus.CLOSED, null, employer, ActivityType.JOB_CLOSED, description);
        return job;
    }

    // AC-E-D4-3: reopen returns the job to the state it was closed FROM (found on the
    // most recent JobStatusChange row, whose toStatus is CLOSED), except a job closed
    // from REJECTED goes to PENDING_APPROVAL like one closed from PENDING_APPROVAL (5.5).
    // The active-job limit is enforced here too (business rule 3).
    @Transactional
    public JobActionResult reopen(Long employerId, Long jobId, ReopenJobForm form) {
        Job job = getOwned(employerId, jobId);
        if (job.getStatus() != JobStatus.CLOSED) {
            throw new BusinessRuleException(NOT_CLOSED_MESSAGE);
        }
        LocalDate today = LocalDate.now(clock);
        validateDeadlineRange(form.getNewDeadline(), today, REOPEN_DEADLINE_RANGE_MESSAGE);

        long activeCount = jobRepository.count(activeSpec(employerId));
        int limit = settingsService.get().getMaxActiveJobsPerEmployer();
        if (activeCount >= limit) {
            throw new BusinessRuleException(activeLimitMessage(activeCount));
        }

        List<JobStatusChange> history = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(jobId);
        JobStatus closedFrom = history.isEmpty() ? null : history.get(history.size() - 1).getFromStatus();

        User employer = job.getEmployer();
        job.setApplicationDeadline(form.getNewDeadline());
        job.setClosedAt(null);

        if (closedFrom == JobStatus.APPROVED) {
            job.setApprovedAt(LocalDateTime.now(clock));
            String description = employer.getCompanyName() + " reopened " + job.getTitle();
            recordStatusChange(job, JobStatus.APPROVED, null, employer, ActivityType.JOB_REOPENED, description);
            return new JobActionResult(job, "Job '" + job.getTitle() + "' reopened and is live again.");
        }
        String description = employer.getCompanyName() + " reopened " + job.getTitle() + " (awaiting approval)";
        recordStatusChange(job, JobStatus.PENDING_APPROVAL, null, employer, ActivityType.JOB_REOPENED, description);
        return new JobActionResult(job, "Job '" + job.getTitle() + "' reopened and sent for approval.");
    }

    // AC-E-D1-3/5.8: allowed only with 0 applications. JobStatusChange rows are removed
    // first (foreign key to the job), then the job itself; JOB_DELETED logs no target
    // because the job is gone (Section 5.7 catalogue).
    @Transactional
    public String delete(Long employerId, Long jobId) {
        Job job = getOwned(employerId, jobId);
        long applications = jobApplicationRepository.countByJob_Id(jobId);
        if (applications > 0) {
            throw new BusinessRuleException(
                    "This job has " + applications + " applications, so it can't be deleted. Close it instead.");
        }
        String title = job.getTitle();
        User employer = job.getEmployer();
        String companyName = employer.getCompanyName();

        List<JobStatusChange> changes = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(jobId);
        jobStatusChangeRepository.deleteAll(changes);
        jobRepository.delete(job);

        activityLogService.log(ActivityType.JOB_DELETED, employer, companyName + " deleted " + title, null, null);
        return title;
    }

    private String activeLimitMessage(long currentActive) {
        return "You already have " + currentActive
                + " active jobs (the limit set by the administrator). Close a job before posting another.";
    }

    private void validateDeadlineRange(LocalDate deadline, LocalDate today, String message) {
        if (deadline == null || deadline.isBefore(today) || deadline.isAfter(today.plusDays(180))) {
            throw new BusinessRuleException(message);
        }
    }

    // Counts toward the active-job limit: PENDING_APPROVAL + APPROVED, expired included
    // (business rule 3).
    private Specification<Job> activeSpec(Long employerId) {
        return JobSpecifications.hasEmployer(employerId)
                .and(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL, JobStatus.APPROVED));
    }

    // ---- Ownership (11.3 contract item 3) ----

    // Used by the detail and edit-form GET pages, and by every action above.
    public Job getOwned(Long employerId, Long jobId) {
        return jobRepository.findByIdAndEmployer_Id(jobId, employerId)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + jobId + " does not exist"));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Logged-in employer no longer exists: " + userId));
    }

    // ---- Job detail (E-D1/E-D4) ----

    // Oldest first, same shape as the admin review page's timeline (Section 6.3 E-D1
    // job-detail note: "invoked in full ... same model attribute and fragment signature
    // as the admin review page").
    public List<JobStatusChange> timeline(Long jobId) {
        return jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(jobId);
    }

    // Quick stats for the job detail page: applications for this one job, broken down by
    // current status. Built from the same per-employer pivot query the history/list
    // pages use (Section 7.9 has no single-job version), filtered down to one job.
    public Map<ApplicationStatus, Long> applicationsByStatus(Long employerId, Long jobId) {
        return applicationStatusCounts(employerId).getOrDefault(jobId, Map.of());
    }

    public long totalApplications(Map<ApplicationStatus, Long> byStatus) {
        long sum = 0;
        for (long value : byStatus.values()) {
            sum += value;
        }
        return sum;
    }

    // ---- Current jobs list (E-D1) ----

    // Not paginated (Section 6.0/7.9: bounded by maxActiveJobsPerEmployer, at most 100):
    // PENDING_APPROVAL, APPROVED and REJECTED jobs, newest first. CLOSED jobs live only
    // in history (E-D4).
    public EmployerJobList employerJobs(Long employerId) {
        Specification<Job> spec = JobSpecifications.hasEmployer(employerId)
                .and(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL, JobStatus.APPROVED, JobStatus.REJECTED));
        List<Job> jobs = jobRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));

        Map<Long, Long> applicationCounts = new HashMap<>();
        for (Job job : jobs) {
            applicationCounts.put(job.getId(), jobApplicationRepository.countByJob_Id(job.getId()));
        }
        long activeCount = jobRepository.count(activeSpec(employerId));
        int maxActiveJobs = settingsService.get().getMaxActiveJobsPerEmployer();
        return new EmployerJobList(jobs, activeCount, maxActiveJobs, applicationCounts);
    }

    // ---- Job history (E-D4) ----

    // Paginated; status is ALL (default, also the fallback for anything unrecognised),
    // LIVE, EXPIRED, PENDING_APPROVAL, REJECTED or CLOSED (Section 7.9 query table).
    public EmployerJobHistory employerJobHistory(Long employerId, String rawStatus, String rawPage) {
        String tab = normaliseHistoryTab(rawStatus);
        LocalDate today = LocalDate.now(clock);

        Specification<Job> spec = JobSpecifications.hasEmployer(employerId);
        switch (tab) {
            case "LIVE" -> spec = spec.and(JobSpecifications.hasStatus(JobStatus.APPROVED))
                    .and(JobSpecifications.deadlineOnOrAfter(today));
            case "EXPIRED" -> spec = spec.and(JobSpecifications.hasStatus(JobStatus.APPROVED))
                    .and(JobSpecifications.deadlineBefore(today));
            case "PENDING_APPROVAL" -> spec = spec.and(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL));
            case "REJECTED" -> spec = spec.and(JobSpecifications.hasStatus(JobStatus.REJECTED));
            case "CLOSED" -> spec = spec.and(JobSpecifications.hasStatus(JobStatus.CLOSED));
            default -> {
                // ALL: no extra status filter
            }
        }
        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Job> page = jobRepository.findAll(spec, pageable);

        Map<Long, Map<ApplicationStatus, Long>> statusCounts = applicationStatusCounts(employerId);
        List<EmployerJobHistoryRow> rows = new ArrayList<>();
        for (Job job : page.getContent()) {
            rows.add(toHistoryRow(job, statusCounts.get(job.getId())));
        }
        return new EmployerJobHistory(page, tab, historySummary(employerId, today), rows);
    }

    private EmployerJobHistoryRow toHistoryRow(Job job, Map<ApplicationStatus, Long> byStatus) {
        long applications = byStatus == null ? 0 : totalApplications(byStatus);
        long hired = byStatus == null ? 0 : byStatus.getOrDefault(ApplicationStatus.HIRED, 0L);
        String closedOn = job.getClosedAt() != null ? DATE_FORMAT.format(job.getClosedAt()) : null;
        return new EmployerJobHistoryRow(job, decisionText(job), closedOn, applications, hired);
    }

    // "Approved 28 Aug 2026" / "Rejected 08 Sep 2026: reason" (Section 6.3 E-D4 column
    // table); a job still waiting on its first decision has neither yet.
    private String decisionText(Job job) {
        if (job.getStatus() == JobStatus.REJECTED) {
            return "Rejected " + DATE_FORMAT.format(job.getUpdatedAt()) + ": " + job.getRejectionReason();
        }
        if (job.getApprovedAt() != null) {
            return "Approved " + DATE_FORMAT.format(job.getApprovedAt());
        }
        return "Awaiting decision";
    }

    private String historySummary(Long employerId, LocalDate today) {
        Specification<Job> forEmployer = JobSpecifications.hasEmployer(employerId);
        long total = jobRepository.count(forEmployer);
        long live = jobRepository.count(forEmployer.and(JobSpecifications.hasStatus(JobStatus.APPROVED))
                .and(JobSpecifications.deadlineOnOrAfter(today)));
        long expired = jobRepository.count(forEmployer.and(JobSpecifications.hasStatus(JobStatus.APPROVED))
                .and(JobSpecifications.deadlineBefore(today)));
        long pending = jobRepository.count(forEmployer.and(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL)));
        long rejected = jobRepository.count(forEmployer.and(JobSpecifications.hasStatus(JobStatus.REJECTED)));
        long closed = jobRepository.count(forEmployer.and(JobSpecifications.hasStatus(JobStatus.CLOSED)));
        return total + " jobs posted: " + live + " live, " + expired + " expired, " + pending + " pending, "
                + rejected + " rejected, " + closed + " closed";
    }

    private String normaliseHistoryTab(String raw) {
        if (raw == null) {
            return "ALL";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LIVE", "EXPIRED", "PENDING_APPROVAL", "REJECTED", "CLOSED" -> upper;
            default -> "ALL";
        };
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

    // One query for every (job, status) pair of the employer's applications (Section 7.9
    // has no single-job version of this pivot), grouped in Java by job id. Reused by the
    // history list and the job detail quick stats.
    private Map<Long, Map<ApplicationStatus, Long>> applicationStatusCounts(Long employerId) {
        Map<Long, Map<ApplicationStatus, Long>> result = new HashMap<>();
        for (JobStatusPairCount row : jobApplicationRepository.countByJobAndStatus(employerId)) {
            result.computeIfAbsent(row.getJobId(), k -> new EnumMap<>(ApplicationStatus.class))
                    .put(row.getStatus(), row.getTotal());
        }
        return result;
    }

    // ---- Result types EmployerJobController needs ----

    // update() and reopen() both need to hand back a job together with the one flash
    // message that matches which transition actually happened (Section 6.3 output
    // tables); a plain Job alone can't tell the controller that afterwards.
    public record JobActionResult(Job job, String message) {
    }

    public record EmployerJobList(List<Job> jobs, long activeCount, int maxActiveJobs,
            Map<Long, Long> applicationCounts) {
    }

    public record EmployerJobHistory(Page<Job> page, String activeTab, String summary, List<EmployerJobHistoryRow> rows) {
    }
}
