package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.web.form.JobReviewForm;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

// Admin job moderation queue (Section 6.2 A-F2/A-D2): approve, reject and take down a
// job posting. Every transition is written through JobService.recordStatusChange
// (11.3 contract item 6, 5.5), so the JobStatusChange row and the activity log entry are
// always in step with the stored status. Take-down setting the job back to REJECTED is
// what makes decision D-3's "a taken-down job always needs a fresh admin review on
// resubmit" rule hold - the employer-side resubmit logic that reacts to REJECTED lives in
// JobService (owned elsewhere), not here.
@Service
public class JobModerationService {

    public static final String NOT_PENDING_MESSAGE = "Only jobs pending approval can be approved or rejected.";
    public static final String DEADLINE_PASSED_MESSAGE =
            "The application deadline has passed. Reject the job and ask the employer to set a new deadline.";
    public static final String EMPLOYER_DISABLED_MESSAGE = "The employer's account is deactivated, so this job can't be approved.";
    public static final String NOT_APPROVED_MESSAGE = "Only live or approved jobs can be taken down.";

    private final JobRepository jobRepository;
    private final JobStatusChangeRepository jobStatusChangeRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final UserRepository userRepository;
    private final JobService jobService;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final Clock clock;

    public JobModerationService(JobRepository jobRepository, JobStatusChangeRepository jobStatusChangeRepository,
            JobApplicationRepository jobApplicationRepository, UserRepository userRepository, JobService jobService,
            SettingsService settingsService, NotificationService notificationService, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobStatusChangeRepository = jobStatusChangeRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.userRepository = userRepository;
        this.jobService = jobService;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    // ---- Queue (A-D2) ----

    // status is PENDING_APPROVAL (default, also the fallback for anything unrecognised),
    // APPROVED, REJECTED, CLOSED or ALL (Section 6.2 A-F2 route table); q searches title
    // or company (Section 7.9). The pending tab is a queue (oldest submitted first); every
    // other tab, ALL included, is newest first.
    public JobQueue queue(String rawStatus, String rawQ, String rawPage) {
        String tab = normaliseTab(rawStatus);
        String q = normaliseQuery(rawQ);

        Specification<Job> spec = "ALL".equals(tab) ? null : JobSpecifications.hasStatus(JobStatus.valueOf(tab));
        if (q != null) {
            Specification<Job> keyword = JobSpecifications.titleOrCompanyContains(q);
            spec = spec == null ? keyword : spec.and(keyword);
        }
        Sort sort = "PENDING_APPROVAL".equals(tab)
                ? Sort.by(Sort.Direction.ASC, "createdAt")
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(), sort);

        Page<Job> jobs = jobRepository.findAll(spec, pageable);
        return new JobQueue(jobs, tab, tabCounts(), applicationCounts(jobs));
    }

    // One small count query per row on the current page (Section 6.2 A-D2 "Applications"
    // column): fine at the page size this app uses (10-50), and JobApplicationRepository
    // has no bulk "count grouped by job id" method to call instead.
    private Map<Long, Long> applicationCounts(Page<Job> jobs) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Job job : jobs.getContent()) {
            counts.put(job.getId(), jobApplicationRepository.countByJob_Id(job.getId()));
        }
        return counts;
    }

    private JobTabCounts tabCounts() {
        long pending = jobRepository.count(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL));
        long approved = jobRepository.count(JobSpecifications.hasStatus(JobStatus.APPROVED));
        long rejected = jobRepository.count(JobSpecifications.hasStatus(JobStatus.REJECTED));
        long closed = jobRepository.count(JobSpecifications.hasStatus(JobStatus.CLOSED));
        long all = jobRepository.count();
        return new JobTabCounts(pending, approved, rejected, closed, all);
    }

    // ---- Review page ----

    public Job getForReview(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job " + jobId + " does not exist"));
    }

    // The job's full status history, oldest first (Section 6.2 A-F2 review page).
    public List<JobStatusChange> timeline(Long jobId) {
        return jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(jobId);
    }

    // ---- Decisions (A-F2) ----

    // AC-A-F2-1/AC-A-D2-1: only a pending job can be approved, its deadline must not have
    // passed and its employer must still be enabled (Section 5.5 transition table).
    @Transactional
    public Job approve(Long jobId, Long adminId) {
        Job job = getForReview(jobId);
        requirePending(job);
        if (job.getApplicationDeadline().isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleException(DEADLINE_PASSED_MESSAGE);
        }
        if (!job.getEmployer().isEnabled()) {
            throw new BusinessRuleException(EMPLOYER_DISABLED_MESSAGE);
        }
        User admin = loadAdmin(adminId);
        job.setApprovedAt(LocalDateTime.now(clock));
        job.setRejectionReason(null);

        String description = admin.getFullName() + " approved " + job.getTitle() + " (" + job.getEmployer().getCompanyName() + ")";
        jobService.recordStatusChange(job, JobStatus.APPROVED, null, admin, ActivityType.JOB_APPROVED, description);

        // Section 16 #1 trigger 3 of 3: "a job is approved or rejected by an admin." Last
        // statement on purpose (NotificationService's class comment explains why).
        notificationService.notifyJobDecision(job.getEmployer().getEmail(), job.getEmployer().getFullName(),
                job.getTitle(), true, null);
        return job;
    }

    // AC-A-F2-2: reason validation (10-500 characters) is enforced by JobReviewForm; only
    // a pending job can be rejected.
    @Transactional
    public Job reject(Long jobId, JobReviewForm form, Long adminId) {
        Job job = getForReview(jobId);
        requirePending(job);
        User admin = loadAdmin(adminId);
        job.setRejectionReason(form.getReason());

        String description = admin.getFullName() + " rejected " + job.getTitle() + " (" + job.getEmployer().getCompanyName() + ")";
        jobService.recordStatusChange(job, JobStatus.REJECTED, form.getReason(), admin, ActivityType.JOB_REJECTED, description);

        // Section 16 #1 trigger 3 of 3, the rejected half. NOT sent by takeDown() below,
        // even though it reuses the same REJECTED status internally (5.5) - the task's
        // list of triggers names only "approved or rejected", and a take-down already has
        // its own, separate "your live job was removed" context that this wording would
        // misstate.
        notificationService.notifyJobDecision(job.getEmployer().getEmail(), job.getEmployer().getFullName(),
                job.getTitle(), false, form.getReason());
        return job;
    }

    // AC-A-F2-3: only an APPROVED job (Live, Expired or Hidden - all share the stored
    // status APPROVED, Section 5.5) can be taken down; applications are left untouched,
    // and the resulting REJECTED status is exactly what makes D-3's forced re-review work
    // once the employer resubmits.
    @Transactional
    public Job takeDown(Long jobId, JobReviewForm form, Long adminId) {
        Job job = getForReview(jobId);
        if (job.getStatus() != JobStatus.APPROVED) {
            throw new BusinessRuleException(NOT_APPROVED_MESSAGE);
        }
        User admin = loadAdmin(adminId);
        job.setRejectionReason(form.getReason());

        String description = admin.getFullName() + " took down " + job.getTitle();
        jobService.recordStatusChange(job, JobStatus.REJECTED, form.getReason(), admin, ActivityType.JOB_TAKEN_DOWN, description);
        return job;
    }

    private void requirePending(Job job) {
        if (job.getStatus() != JobStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException(NOT_PENDING_MESSAGE);
        }
    }

    private User loadAdmin(Long adminId) {
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Logged-in admin no longer exists: " + adminId));
    }

    // PENDING_APPROVAL (default, also the fallback for anything unrecognised), APPROVED,
    // REJECTED, CLOSED or ALL (Section 6.2 A-F2 route table, Section 7.9 binding rule).
    private String normaliseTab(String raw) {
        if (raw == null) {
            return "PENDING_APPROVAL";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "APPROVED", "REJECTED", "CLOSED", "ALL" -> upper;
            default -> "PENDING_APPROVAL";
        };
    }

    private String normaliseQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int parsePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(value, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // What AdminJobController needs to render admin/jobs.html: the current page, which
    // tab is active (for tab highlighting and the pending-only oldest-first note), the
    // counts shown on every tab, and each row's application count keyed by job id.
    public record JobQueue(Page<Job> jobs, String activeTab, JobTabCounts counts, Map<Long, Long> applicationCounts) {
    }

    public record JobTabCounts(long pending, long approved, long rejected, long closed, long all) {
    }
}
