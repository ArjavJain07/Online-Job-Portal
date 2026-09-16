package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.JobSearchResult;
import com.jobportal.dto.NormalisedCriteria;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.web.form.JobSearchCriteria;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Job search for visitors and (from M5) seekers, plus the public job detail lookup and
// view counting (Section 6.1 P-2, 6.4 S-F1, 7.9, decision D-19). Everything here reads or
// writes through JobRepository/JobApplicationRepository and JobSpecifications - no
// controller queries a repository directly (Section 3.1).
@Service
public class JobSearchService {

    private static final String VIEWED_JOB_IDS_SESSION_ATTRIBUTE = "viewedJobIds";
    private static final Set<Integer> EXPERIENCE_OPTIONS = Set.of(0, 1, 3, 5, 10);
    private static final Set<Integer> POSTED_WITHIN_OPTIONS = Set.of(1, 7, 30);
    private static final int MAX_SALARY = 100_000_000;
    private static final int TEXT_FILTER_MAX_LENGTH = 100;

    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final SettingsService settingsService;
    private final Clock clock;

    public JobSearchService(JobRepository jobRepository, JobApplicationRepository jobApplicationRepository,
            SettingsService settingsService, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    // ---- Search (S-F1) ----

    // Builds the Specification from every filter that parsed, always starting from
    // live(today) so pending, rejected, closed, expired and hidden jobs never appear
    // (Section 3.5 rule 4, one definition of Live).
    public JobSearchResult search(JobSearchCriteria raw) {
        NormalisedCriteria criteria = normalise(raw);
        Specification<Job> spec = JobSpecifications.live(LocalDate.now(clock));
        if (criteria.q() != null) {
            spec = spec.and(JobSpecifications.keyword(criteria.q()));
        }
        if (criteria.location() != null) {
            spec = spec.and(JobSpecifications.locationContains(criteria.location()));
        }
        if (criteria.category() != null) {
            spec = spec.and(JobSpecifications.hasCategory(criteria.category()));
        }
        if (criteria.jobType() != null) {
            spec = spec.and(JobSpecifications.hasJobType(criteria.jobType()));
        }
        if (criteria.workMode() != null) {
            spec = spec.and(JobSpecifications.hasWorkMode(criteria.workMode()));
        }
        if (criteria.minSalary() != null) {
            spec = spec.and(JobSpecifications.salaryAtLeast(criteria.minSalary()));
        }
        if (criteria.maxExperience() != null) {
            spec = spec.and(JobSpecifications.maxExperience(criteria.maxExperience()));
        }
        if (criteria.postedWithin() != null) {
            spec = spec.and(JobSpecifications.approvedSince(LocalDateTime.now(clock).minusDays(criteria.postedWithin())));
        }
        Pageable pageable = PageRequest.of(criteria.page(), settingsService.get().getPageSize(), sortFor(criteria.sort()));
        Page<Job> jobs = jobRepository.findAll(spec, pageable);
        return new JobSearchResult(jobs, criteria, criteria.warnings());
    }

    // Whitelisted sort options only (Section 7.9): user input is never passed straight to
    // Sort.by(...).
    private Sort sortFor(String sort) {
        return switch (sort) {
            case "salary" -> Sort.by(Sort.Order.desc("salaryMax"), Sort.Order.desc("id"));
            case "deadline" -> Sort.by(Sort.Order.asc("applicationDeadline"), Sort.Order.desc("id"));
            default -> Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id"));
        };
    }

    // Parses every raw string field once, collecting a warning only for the one filter
    // whose problem is worth explaining to the visitor (an unparsable minSalary). Every
    // other bad value (an unknown category, an out-of-range experience, a negative page)
    // is simply dropped, matching the criteria table in Section 6.4 S-F1.
    private NormalisedCriteria normalise(JobSearchCriteria raw) {
        List<String> warnings = new ArrayList<>();
        String q = trimToLength(raw.getQ());
        String location = trimToLength(raw.getLocation());
        JobCategory category = parseEnum(JobCategory.class, raw.getCategory());
        JobType jobType = parseEnum(JobType.class, raw.getJobType());
        WorkMode workMode = parseEnum(WorkMode.class, raw.getWorkMode());
        Integer minSalary = parseMinSalary(raw.getMinSalary(), warnings);
        Integer maxExperience = parseFromOptions(raw.getMaxExperience(), EXPERIENCE_OPTIONS);
        Integer postedWithin = parseFromOptions(raw.getPostedWithin(), POSTED_WITHIN_OPTIONS);
        String sort = parseSort(raw.getSort());
        int page = parsePage(raw.getPage());
        return new NormalisedCriteria(q, location, category, jobType, workMode, minSalary, maxExperience,
                postedWithin, sort, page, warnings);
    }

    private String trimToLength(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > TEXT_FILTER_MAX_LENGTH ? trimmed.substring(0, TEXT_FILTER_MAX_LENGTH) : trimmed;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Integer parseMinSalary(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Integer value = parseIntOrNull(raw);
        if (value != null && value >= 0 && value <= MAX_SALARY) {
            return value;
        }
        warnings.add("Minimum salary must be a whole number, so that filter was ignored.");
        return null;
    }

    private Integer parseFromOptions(String raw, Set<Integer> allowedValues) {
        Integer value = parseIntOrNull(raw);
        return value != null && allowedValues.contains(value) ? value : null;
    }

    private Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String parseSort(String raw) {
        if ("salary".equals(raw) || "deadline".equals(raw)) {
            return raw;
        }
        return "newest";
    }

    private int parsePage(String raw) {
        Integer value = parseIntOrNull(raw);
        return value != null && value >= 0 ? value : 0;
    }

    // ---- Public job detail (P-2) ----

    // Loads a job for the detail page, enforcing "who can see which job" (Section 6.1
    // P-2): not found means 404 whether the id does not exist or the viewer is not allowed
    // to see it (Section 3.5 rule 3). viewerId/viewerRole are both null for an anonymous
    // visitor.
    public Job findForDetail(Long jobId, Long viewerId, Role viewerRole) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + jobId + " does not exist"));
        if (!visibleTo(job, viewerId, viewerRole)) {
            throw new ResourceNotFoundException("Job " + jobId + " is not visible to this viewer");
        }
        return job;
    }

    // Checked in this order: a deactivated employer hides every one of its jobs from
    // everyone but an admin; a job still awaiting a decision (or turned down) is a preview
    // only its own employer or an admin may open; every other stored status (approved -
    // whether still Live or expired - and closed) is visible to anyone.
    private boolean visibleTo(Job job, Long viewerId, Role viewerRole) {
        boolean admin = viewerRole == Role.ADMIN;
        boolean owner = viewerRole == Role.EMPLOYER && job.getEmployer().getId().equals(viewerId);
        if (!job.getEmployer().isEnabled()) {
            return admin;
        }
        if (job.getStatus() == JobStatus.PENDING_APPROVAL || job.getStatus() == JobStatus.REJECTED) {
            return admin || owner;
        }
        return true;
    }

    // The seeker's own application to this job, if any, for the apply panel ("You applied
    // on ... (Status: ...)"). Empty for anyone who is not a job seeker.
    public Optional<JobApplication> findSeekerApplication(Job job, Long viewerId, Role viewerRole) {
        if (viewerRole != Role.JOB_SEEKER) {
            return Optional.empty();
        }
        return jobApplicationRepository.findByJob_IdAndSeeker_Id(job.getId(), viewerId);
    }

    // ---- View counting (decision D-19) ----

    // Increments viewCount with one bulk update, only when the job is Live, the viewer is
    // not its own employer or an admin, and this session has not already counted a view of
    // this job (Section 3.5 rule 5: GET may only write view counts and read markers).
    @Transactional
    public void recordView(Job job, HttpSession session, Long viewerId, Role viewerRole) {
        if (!job.isLive(LocalDate.now(clock))) {
            return;
        }
        boolean owner = viewerRole == Role.EMPLOYER && job.getEmployer().getId().equals(viewerId);
        boolean admin = viewerRole == Role.ADMIN;
        if (owner || admin) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<Long> viewedJobIds = (Set<Long>) session.getAttribute(VIEWED_JOB_IDS_SESSION_ATTRIBUTE);
        if (viewedJobIds == null) {
            viewedJobIds = new HashSet<>();
            session.setAttribute(VIEWED_JOB_IDS_SESSION_ATTRIBUTE, viewedJobIds);
        }
        if (viewedJobIds.add(job.getId())) {
            jobRepository.incrementViewCount(job.getId());
        }
    }

    // ---- Landing page (P-1) ----

    // The newest Live jobs, for the landing page's "latest openings" list.
    public List<Job> latestLiveJobs(int limit) {
        Specification<Job> spec = JobSpecifications.live(LocalDate.now(clock));
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id")));
        return jobRepository.findAll(spec, pageable).getContent();
    }

    // "N live jobs" counter.
    public long countLiveJobs() {
        return jobRepository.count(JobSpecifications.live(LocalDate.now(clock)));
    }

    // "N companies hiring" counter: employers with at least one Live job right now.
    public long countEmployersHiring() {
        return jobRepository.countDistinctEmployersWithLiveJobs(JobStatus.APPROVED, LocalDate.now(clock));
    }

    // ---- Two-pane jobs page (Section 7.1 core rule 7) ----

    // Small, additive helper for JobBrowseController#search and SeekerJobController#search:
    // the job shown in the right-hand ".jp-jobs-detail" pane is the requested "job" id when
    // it is one of this result page's jobs, otherwise the first job on the page (or null
    // when the page has none). Does not change what search() returns, and never counts a
    // view - view counting stays exactly where it is today, in recordView() above, called
    // only from the standalone GET /jobs/{id} route.
    public Job selectForPane(List<Job> pageContent, Long requestedJobId) {
        if (pageContent.isEmpty()) {
            return null;
        }
        if (requestedJobId != null) {
            for (Job job : pageContent) {
                if (job.getId().equals(requestedJobId)) {
                    return job;
                }
            }
        }
        return pageContent.get(0);
    }
}
