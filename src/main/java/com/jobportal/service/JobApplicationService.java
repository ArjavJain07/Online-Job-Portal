package com.jobportal.service;

import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.CandidateProfile;
import com.jobportal.dto.SeekerApplicationHistoryRow;
import com.jobportal.dto.SeekerJobRow;
import com.jobportal.dto.StoredFile;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.FileValidationException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.repository.projection.ApplicationIdCount;
import com.jobportal.web.form.ApplicationForm;
import com.jobportal.web.form.ApplicationStatusForm;
import com.jobportal.web.form.InternalNoteForm;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
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

    // The seeker-side "accepted status values" for S-D2 (Section 6.4, 7.9 binding rule):
    // a raw ?status= outside this set (blank, unrecognised, or a final status such as
    // HIRED which belongs to S-D4 instead) falls back to "ALL".
    private static final Set<String> ACTIVE_STATUS_FILTERS = Set.of("APPLIED", "UNDER_REVIEW", "SHORTLISTED", "INTERVIEW");

    // S-D4's "final applications" (Hired, Not selected, Withdrawn - I-6): the default
    // view=final status set, and also the only values ?result= ever accepts besides ALL.
    private static final Set<ApplicationStatus> FINAL_STATUSES =
            EnumSet.of(ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN);
    private static final Set<String> FINAL_STATUSES_NAMES = Set.of("HIRED", "REJECTED", "WITHDRAWN");

    // Shared with SeekerJobController, the same way EmployerJobController reuses
    // JobService's own public message constants: the controller's own pre-check (Section
    // 6.4 S-F2 "Check order") needs the exact same wording as the defence-in-depth checks
    // inside apply() below, without the two ever drifting apart.
    public static final String ALREADY_APPLIED_MESSAGE = "You have already applied for this job.";
    public static final String NOT_LIVE_MESSAGE = "This job is no longer accepting applications.";

    private static final DateTimeFormatter HISTORY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final JobApplicationRepository jobApplicationRepository;
    private final ApplicationStatusChangeRepository applicationStatusChangeRepository;
    private final JobRepository jobRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final ActivityLogService activityLogService;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final Clock clock;

    public JobApplicationService(JobApplicationRepository jobApplicationRepository,
            ApplicationStatusChangeRepository applicationStatusChangeRepository, JobRepository jobRepository,
            SeekerProfileRepository seekerProfileRepository, MessageRepository messageRepository,
            UserRepository userRepository, FileStorageService fileStorageService,
            ActivityLogService activityLogService, SettingsService settingsService,
            NotificationService notificationService, Clock clock) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.applicationStatusChangeRepository = applicationStatusChangeRepository;
        this.jobRepository = jobRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.activityLogService = activityLogService;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
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

        // Section 16 #1 trigger 2 of 3: "a candidate's application status changes."
        // Deliberately gated on the ACTOR, not the new status: this shared helper is also
        // reached by withdraw() with actor = the seeker themselves, and a candidate needs
        // no email informing them of the action they just took. Every employer-driven
        // transition DOES go through here, including each row of a bulk status change
        // (JobApplicationService#bulkChangeStatus calls changeStatus() once per id, which
        // calls this), so one email is sent per application either way. Kept as the LAST
        // statement in this method on purpose - see NotificationService's class comment on
        // why a plain @Async call (not @TransactionalEventListener) was chosen, and what
        // that trade-off requires of every call site.
        if (actor.getRole() == Role.EMPLOYER) {
            notificationService.notifyApplicationStatusChanged(application.getSeeker().getEmail(),
                    application.getSeeker().getFullName(), application.getJob().getTitle(), newStatus.getSeekerLabel(),
                    application.getReference());
        }
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

    // ---- CSV export (new feature: the E-D2 list, in full, as a file) ----

    // Same ownership/filter resolution as listForEmployer above (jobId/status raw
    // strings, Section 7.9 binding rule) and the SAME findForEmployer query - so an
    // export can never show a row the paginated list itself would not - just unpaged and
    // sorted the same "newest applied first" way, since the file must hold the WHOLE
    // filtered list the employer is looking at, not only the one page on screen.
    public List<JobApplication> exportForEmployer(Long employerId, String rawJobId, String rawStatus) {
        Long jobId = ownJobIdOrNull(employerId, rawJobId);
        Set<ApplicationStatus> statuses = statusesFor(normaliseStatus(rawStatus));
        Pageable unpaged = Pageable.unpaged(Sort.by(Sort.Direction.DESC, "appliedAt"));
        return jobApplicationRepository.findForEmployer(employerId, jobId, statuses, unpaged).getContent();
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
                profile == null ? null : profile.getHeadline(),
                profile == null ? List.<String>of() : profile.skillList(),
                profile == null ? 0 : profile.getExperienceYears(), profile == null ? null : profile.getEducation(),
                profile == null ? null : profile.getAbout());
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

    // ---- Bulk status change (employer applications list, new feature) ----

    // One employer action, many applications, but still exactly ONE place a status is
    // ever allowed to move: this loops over changeStatus(id, employerId, form) itself
    // rather than re-deriving canTransitionTo/recordStatusChange here, so the 14-of-49
    // matrix (Section 5.6, ApplicationStatusTest) and the ApplicationStatusChange row it
    // writes can never drift between the one-at-a-time page and this one.
    //
    // A mixed selection is applied wherever the matrix allows it and skipped wherever it
    // does not, deliberately NOT all-or-nothing: REJECTED - the status a bulk action is
    // most likely to target, since it is the only one legal from every active status
    // (APPLIED, UNDER_REVIEW, SHORTLISTED and INTERVIEW all allow it) - is the exception,
    // not the rule; a realistic multi-row selection almost always mixes current statuses,
    // so failing the WHOLE batch because one row is already Hired or already Rejected
    // would make "bulk" pointless for every target status except that one. Nothing is
    // ever silently dropped either way: every id the caller sent comes back in exactly
    // one of updated()/skipped(), which EmployerApplicationController turns into one
    // flash message that names every skipped row and why.
    @Transactional
    public BulkStatusResult bulkChangeStatus(List<Long> applicationIds, Long employerId, ApplicationStatus newStatus) {
        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        ApplicationStatusForm form = new ApplicationStatusForm();
        form.setStatus(newStatus);
        // No noteToCandidate: E-F2's optional note is a one-off aside to a single
        // candidate and has no single meaning spread across a mixed batch; an employer
        // who wants to say something candidate-specific still has the one-at-a-time page.

        for (Long id : new LinkedHashSet<>(applicationIds)) { // de-duplicated, first occurrence kept
            Optional<JobApplication> owned = jobApplicationRepository.findByIdAndJob_Employer_Id(id, employerId);
            if (owned.isEmpty()) {
                // Not this employer's application (wrong owner, or a stale/hand-crafted
                // id) - the same ownership rule getForEmployer enforces one row at a time
                // (Section 4.5), just reported instead of thrown so the rest of the batch
                // still runs.
                skipped.add("Application " + id + " (not found)");
                continue;
            }
            String reference = owned.get().getReference();
            try {
                changeStatus(id, employerId, form);
                updated.add(reference);
            } catch (BusinessRuleException e) {
                skipped.add(reference + " (currently " + owned.get().getStatus().getLabel() + ")");
            }
        }
        return new BulkStatusResult(newStatus, updated, skipped);
    }

    // What EmployerApplicationController turns into the "N updated, M skipped" flash: the
    // target status plus the reference of every application that moved and a short reason
    // for every one that did not, in submission order. Deliberately not just a count: this
    // feature's whole point is that the employer must end up understanding exactly what
    // happened to their selection, so a skipped row has to be nameable, not just countable.
    public record BulkStatusResult(ApplicationStatus newStatus, List<String> updated, List<String> skipped) {
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

    // ==================== M5: seeker side (Section 6.4 S-F1, S-D1, S-F2, S-F3, S-D4) ====================

    // ---- Search "Applied" badges (S-F1, S-D1, 7.9) ----

    // /seeker/jobs reuses JobSearchService.search() for the actual filtering/paging, then
    // pairs each job on the page with whether this seeker has already applied to it (any
    // status, including withdrawn - I-15) in one query (Section 7.9 "Applied badges").
    // Returned as ready-made rows, not a bare Set<Long>, because a fragment call cannot
    // safely evaluate appliedJobIds.contains(job.id) itself (see SeekerJobRow).
    public List<SeekerJobRow> withAppliedFlags(List<Job> jobs, Long seekerId) {
        List<Long> jobIds = new ArrayList<>();
        for (Job job : jobs) {
            jobIds.add(job.getId());
        }
        Set<Long> appliedJobIds = jobApplicationRepository.findJobIdsBySeekerAndJobIdIn(seekerId, jobIds);
        List<SeekerJobRow> rows = new ArrayList<>();
        for (Job job : jobs) {
            rows.add(new SeekerJobRow(job, appliedJobIds.contains(job.getId())));
        }
        return rows;
    }

    // ---- Apply (S-F2) ----

    // Loads the job an apply request targets. A missing id is a plain 404, the same way
    // every other detail page treats an unknown id (Section 6.6); whether the job is
    // still Live is a separate, business-rule check the controller makes with
    // job.isLive(today) (Section 11.3 contract item 4: never re-write that condition).
    public Job getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + jobId + " does not exist"));
    }

    // "One application per seeker per job" (S-F2 business rule 3): the controller calls
    // this before rendering the apply form and again before accepting a submission, so a
    // seeker who already applied - even if they later withdrew (I-15) - is always sent
    // back to that existing application instead of a second one, whatever the job's
    // current status is (Section 6.4 S-F2 "Check order").
    public Optional<JobApplication> findExisting(Long jobId, Long seekerId) {
        return jobApplicationRepository.findByJob_IdAndSeeker_Id(jobId, seekerId);
    }

    public boolean hasProfileResume(Long seekerId) {
        return seekerProfileRepository.findByUser_Id(seekerId)
                .map(profile -> profile.getResumeStoredName() != null)
                .orElse(false);
    }

    // Everything seeker/apply.html needs about the seeker's own profile resume and the
    // current upload rules (Section 6.4 S-F2 screen, Section 7.5): built once here, from
    // SettingsService, rather than re-parsed inside the template (Section 7.1).
    public ResumeOptions resumeOptions(Long seekerId) {
        SeekerProfile profile = seekerProfileRepository.findByUser_Id(seekerId).orElse(null);
        boolean hasProfileResume = profile != null && profile.getResumeStoredName() != null;
        SystemSettings settings = settingsService.get();
        List<String> allowedTypes = parseAllowedTypes(settings.getAllowedResumeTypes());
        return new ResumeOptions(profile, hasProfileResume, buildAcceptAttribute(allowedTypes),
                buildResumeHint(allowedTypes, settings.getMaxResumeSizeMb()), settings.getMaxResumeSizeMb());
    }

    private List<String> parseAllowedTypes(String csv) {
        List<String> types = new ArrayList<>();
        for (String type : csv.split(",")) {
            String trimmed = type.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types;
    }

    // ".pdf,.doc,.docx" for the file input's accept attribute (Section 6.4 S-F2 screen:
    // "accept built from the allowed types").
    private String buildAcceptAttribute(List<String> allowedTypes) {
        List<String> withDot = new ArrayList<>();
        for (String type : allowedTypes) {
            withDot.add("." + type);
        }
        return String.join(",", withDot);
    }

    // "PDF, DOC or DOCX, max 2 MB" (Section 6.4 S-F2 screen hint). Builds the same
    // "X, Y or Z" list FileStorageService's own (private) allowedTypesMessage does for its
    // rejection message - duplicated rather than shared because that method is private and
    // phrased differently ("Only ... files are allowed."), and this file cannot edit
    // FileStorageService (Section 7.4, outside this slice's file ownership).
    private String buildResumeHint(List<String> allowedTypes, int maxResumeSizeMb) {
        List<String> upper = new ArrayList<>();
        for (String type : allowedTypes) {
            upper.add(type.toUpperCase(Locale.ROOT));
        }
        String joined = upper.size() == 1
                ? upper.get(0)
                : String.join(", ", upper.subList(0, upper.size() - 1)) + " or " + upper.get(upper.size() - 1);
        return joined + ", max " + maxResumeSizeMb + " MB";
    }

    // AC-S-F2-1/AC-S-F2-2/AC-S-F2-3: the duplicate and Live checks are repeated here (the
    // controller already made both before calling apply(), Section 6.4 S-F2 "Check
    // order") purely as defence in depth against a hand-crafted or racing request; the
    // unique constraint on (job_id, seeker_id) is the final backstop for two concurrent
    // submissions racing past both checks (business rule 3).
    @Transactional
    public JobApplication apply(Job job, Long seekerId, ApplicationForm form) {
        if (jobApplicationRepository.findByJob_IdAndSeeker_Id(job.getId(), seekerId).isPresent()) {
            throw new BusinessRuleException(ALREADY_APPLIED_MESSAGE);
        }
        if (!job.isLive(LocalDate.now(clock))) {
            throw new BusinessRuleException(NOT_LIVE_MESSAGE);
        }

        User seeker = loadUser(seekerId);
        SeekerProfile profile = seekerProfileRepository.findByUser_Id(seekerId).orElse(null);
        StoredFile resume = resolveApplicationResume(form, profile);

        LocalDateTime now = LocalDateTime.now(clock);
        JobApplication application = new JobApplication();
        application.setJob(job);
        application.setSeeker(seeker);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setCoverLetter(form.getCoverLetter());
        application.setResumeStoredName(resume.storedName());
        application.setResumeOriginalName(resume.originalName());
        application.setResumeContentType(resume.contentType());
        application.setResumeSizeBytes(resume.sizeBytes());
        application.setAppliedAt(now);
        application.setUpdatedAt(now);

        // The defence-in-depth backstop (business rule 3): if a concurrent request for
        // the same job and seeker won the race between the check above and this insert,
        // uk_application_job_seeker rejects the second row: the resume copy just made for
        // it is deleted immediately (not deleteAfterCommit - this transaction is about to
        // roll back, so "after commit" would never run) and the same message is shown.
        try {
            jobApplicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            fileStorageService.deleteNow(resume.storedName());
            throw new BusinessRuleException(ALREADY_APPLIED_MESSAGE);
        }

        // Business rule 5: only once the application's own copy is safely saved does a
        // ticked "save to profile" make a SECOND, independent copy for the profile, so a
        // later profile replacement never touches the copy an employer has already seen
        // (I-14).
        if (form.getResumeChoice() == ApplicationForm.ResumeChoice.UPLOAD && form.isSaveToProfile()) {
            copyIntoProfile(seeker, profile, resume);
        }

        // apply() sets the initial APPLIED status itself rather than calling
        // recordStatusChange (Section 11.3 contract item 6, class comment above): there is
        // no previous status to record a transition from.
        ApplicationStatusChange change = new ApplicationStatusChange();
        change.setApplication(application);
        change.setFromStatus(null);
        change.setToStatus(ApplicationStatus.APPLIED);
        change.setActorName(actorDisplayName(seeker));
        change.setActorRole(seeker.getRole());
        change.setChangedAt(now);
        applicationStatusChangeRepository.save(change);

        String description = seeker.getFullName() + " applied for " + job.getTitle() + " at "
                + job.getEmployer().getCompanyName() + " (" + application.getReference() + ")";
        activityLogService.log(ActivityType.APPLICATION_SUBMITTED, seeker, description, TargetType.APPLICATION,
                application.getId());

        // Section 16 #1 trigger 1 of 3: "an employer's job receives an application." Last
        // statement in this method on purpose (NotificationService's class comment
        // explains why); everything it needs is read here, inside this still-open
        // transaction, rather than handed the job/seeker entities themselves.
        notificationService.notifyApplicationReceived(job.getEmployer().getEmail(), job.getEmployer().getFullName(),
                job.getTitle(), seeker.getFullName(), application.getReference());

        return application;
    }

    // Section 6.4 S-F2 business rule 5: a PROFILE choice copies the seeker's current
    // profile resume; an UPLOAD choice stores the submitted file fresh. Either way the
    // application gets its own file under a brand new UUID name, never the profile's own
    // stored name, so replacing the profile resume later can never change what this
    // application already has (I-14).
    private StoredFile resolveApplicationResume(ApplicationForm form, SeekerProfile profile) {
        if (form.getResumeChoice() == ApplicationForm.ResumeChoice.PROFILE) {
            if (profile == null || profile.getResumeStoredName() == null) {
                // Defence in depth: the controller already rejects this combination as a
                // resumeChoice field error before ever calling apply() (Section 6.4 S-F2
                // field table), so this only fires for a hand-crafted request.
                throw new FileValidationException(ApplicationForm.RESUME_CHOICE_MESSAGE);
            }
            return fileStorageService.copy(profile.getResumeStoredName());
        }
        return fileStorageService.store(form.getResumeFile());
    }

    // Business rule 5's "also copied into the profile": a second, independent copy of the
    // just-uploaded file becomes the new profile resume, and the old profile file (if any)
    // is deleted only after this transaction commits (Section 5.8), so a rolled-back apply
    // never loses the seeker's existing profile resume.
    private void copyIntoProfile(User seeker, SeekerProfile profile, StoredFile applicationResume) {
        StoredFile profileCopy = fileStorageService.copy(applicationResume.storedName());
        String oldStoredName = profile == null ? null : profile.getResumeStoredName();

        SeekerProfile target = profile;
        if (target == null) {
            target = new SeekerProfile();
            target.setUser(seeker);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        target.setResumeStoredName(profileCopy.storedName());
        target.setResumeOriginalName(profileCopy.originalName());
        target.setResumeContentType(profileCopy.contentType());
        target.setResumeSizeBytes(profileCopy.sizeBytes());
        target.setResumeUploadedAt(now);
        target.setUpdatedAt(now);
        seekerProfileRepository.save(target);

        if (oldStoredName != null) {
            fileStorageService.deleteAfterCommit(oldStoredName);
        }
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Logged-in job seeker no longer exists: " + userId));
    }

    // ---- Detail (S-F3), withdraw (S-F3) ----

    // Ownership (Section 4.5, 11.3 contract item 3): only the seeker who submitted the
    // application may load it.
    public JobApplication getForSeeker(Long applicationId, Long seekerId) {
        return jobApplicationRepository.findByIdAndSeeker_Id(applicationId, seekerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
    }

    // S-F3 "Detail page order of work" / Section 7.10: loads the owned application, stamps
    // seekerLastViewedAt (clearing the "Updated" badge, Section 6.5.3) and runs the
    // message read-marker bulk update, in that order, so the pending seekerLastViewedAt
    // change is flushed before the JPQL bulk update (Hibernate only auto-flushes pending
    // changes that touch the table the update targets, 7.10) - whoever loads the thread's
    // messages for the page does so only after calling this.
    @Transactional
    public JobApplication viewApplicationForSeeker(Long applicationId, Long seekerId) {
        JobApplication application = getForSeeker(applicationId, seekerId);
        LocalDateTime now = LocalDateTime.now(clock);
        application.setSeekerLastViewedAt(now);
        jobApplicationRepository.save(application);
        messageRepository.markThreadRead(applicationId, seekerId, now);
        return application;
    }

    // AC-S-F3-2: withdraw only from an active status (Section 5.6 isActive()); the shared
    // history helper (11.3 contract item 6) sets statusChangedAt AND seekerLastViewedAt to
    // the same instant, so the seeker's own withdrawal never raises an "Updated" badge on
    // an application that has just moved to History (Section 6.4 S-F3 business rules).
    @Transactional
    public JobApplication withdraw(Long applicationId, Long seekerId) {
        JobApplication application = getForSeeker(applicationId, seekerId);
        if (!application.getStatus().isActive()) {
            throw new BusinessRuleException("This application can no longer be withdrawn (status: "
                    + application.getStatus().getSeekerLabel() + ").");
        }
        User seeker = application.getSeeker();
        String description = actorDisplayName(seeker) + " withdrew " + application.getReference();
        recordStatusChange(application, ApplicationStatus.WITHDRAWN, null, seeker,
                ActivityType.APPLICATION_WITHDRAWN, description);
        return application;
    }

    // ---- Active applications list (S-D2) ----

    // Not paginated (Section 7.9: "one row per application in progress", bounded by how
    // many applications a seeker can have active at once). Always fetches all four active
    // statuses so the chip counts ("All 3 - Applied 1 - Under review 1 - Interview 1")
    // stay the unfiltered totals even while a specific chip narrows the visible rows.
    public SeekerActiveApplications activeApplicationsForSeeker(Long seekerId, String rawStatus) {
        String status = normaliseActiveStatus(rawStatus);
        Sort sort = Sort.by(Sort.Order.desc("statusChangedAt"), Sort.Order.desc("appliedAt"));
        List<JobApplication> applications = jobApplicationRepository.findBySeeker_IdAndStatusIn(seekerId,
                ACTIVE_STATUSES, sort);

        long appliedCount = 0;
        long underReviewCount = 0;
        long shortlistedCount = 0;
        long interviewCount = 0;
        for (JobApplication application : applications) {
            switch (application.getStatus()) {
                case APPLIED -> appliedCount++;
                case UNDER_REVIEW -> underReviewCount++;
                case SHORTLISTED -> shortlistedCount++;
                case INTERVIEW -> interviewCount++;
                default -> { /* the query already limits results to the four active statuses */ }
            }
        }

        List<JobApplication> visible = new ArrayList<>();
        for (JobApplication application : applications) {
            if ("ALL".equals(status) || application.getStatus().name().equals(status)) {
                visible.add(application);
            }
        }

        return new SeekerActiveApplications(visible, status, applications.size(), appliedCount, underReviewCount,
                shortlistedCount, interviewCount, unreadCountsByApplication(seekerId));
    }

    // "ALL" (missing, blank or unrecognised - including a final status such as HIRED,
    // which belongs to S-D4 instead, Section 6.4 S-D2 "Accepted values") or one of the
    // four active status names.
    private String normaliseActiveStatus(String raw) {
        if (raw == null) {
            return "ALL";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return ACTIVE_STATUS_FILTERS.contains(upper) ? upper : "ALL";
    }

    // ---- Application history (S-D4) ----

    public SeekerApplicationHistory applicationHistoryForSeeker(Long seekerId, String rawResult, String rawView,
            String rawPage) {
        String view = "all".equalsIgnoreCase(rawView) ? "all" : "final";
        ApplicationStatus resultFilter = parseResultFilter(rawResult);
        Set<ApplicationStatus> statuses;
        if (resultFilter != null) {
            statuses = EnumSet.of(resultFilter);
        } else if ("all".equals(view)) {
            statuses = EnumSet.allOf(ApplicationStatus.class);
        } else {
            statuses = FINAL_STATUSES;
        }

        Sort sort = Sort.by(Sort.Order.desc("statusChangedAt"), Sort.Order.desc("appliedAt"));
        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(), sort);
        Page<JobApplication> page = jobApplicationRepository.findBySeeker_IdAndStatusIn(seekerId, statuses, pageable);

        List<SeekerApplicationHistoryRow> rows = new ArrayList<>();
        for (JobApplication application : page.getContent()) {
            rows.add(toHistoryRow(application));
        }

        String result = resultFilter == null ? "ALL" : resultFilter.name();
        return new SeekerApplicationHistory(page, rows, view, result, summaryForSeeker(seekerId));
    }

    // "ALL" (missing, blank or unrecognised) or one of the three final statuses (Section
    // 6.4 S-D4 "Accepted values"). A result value narrows to exactly that status
    // regardless of view - "a result value on view=all still filters by current status".
    private ApplicationStatus parseResultFilter(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!FINAL_STATUSES_NAMES.contains(upper)) {
            return null;
        }
        return ApplicationStatus.valueOf(upper);
    }

    private SeekerApplicationHistoryRow toHistoryRow(JobApplication application) {
        ApplicationStatus status = application.getStatus();
        boolean decided = status.isFinal() && application.getStatusChangedAt() != null;
        String resultLabel = decided ? status.getSeekerLabel() : "In progress";
        String decidedOnText = decided ? application.getStatusChangedAt().format(HISTORY_DATE_FORMAT) : "In progress";
        String durationText = decided ? formatDuration(application.getAppliedAt(), application.getStatusChangedAt()) : "-";
        return new SeekerApplicationHistoryRow(application, resultLabel, decidedOnText, durationText);
    }

    private String formatDuration(LocalDateTime appliedAt, LocalDateTime decidedAt) {
        long days = ChronoUnit.DAYS.between(appliedAt.toLocalDate(), decidedAt.toLocalDate());
        return days == 1 ? "1 day" : days + " days";
    }

    // "You have applied to 4 jobs: 0 hired, 1 not selected, 0 withdrawn, 3 in progress."
    // (Section 6.4 S-D4 screen) - always the seeker's full application history, regardless
    // of the current view/result filter, so the summary line never changes as the chips do.
    private String summaryForSeeker(Long seekerId) {
        List<JobApplication> all = jobApplicationRepository.findBySeeker_IdAndStatusIn(seekerId,
                EnumSet.allOf(ApplicationStatus.class), Sort.unsorted());
        long hired = 0;
        long rejected = 0;
        long withdrawn = 0;
        long inProgress = 0;
        for (JobApplication application : all) {
            switch (application.getStatus()) {
                case HIRED -> hired++;
                case REJECTED -> rejected++;
                case WITHDRAWN -> withdrawn++;
                default -> inProgress++;
            }
        }
        return "You have applied to " + all.size() + " jobs: " + hired + " hired, " + rejected + " not selected, "
                + withdrawn + " withdrawn, " + inProgress + " in progress.";
    }

    // Everything seeker/apply.html needs about the profile resume option and the current
    // upload rules (Section 6.4 S-F2, Section 7.5): profile is null only for the rare
    // seeker with no SeekerProfile row at all (should not happen after registration, but
    // candidateProfile() above treats it the same defensive way).
    public record ResumeOptions(SeekerProfile profile, boolean hasProfileResume, String acceptAttribute,
            String resumeHint, int maxResumeSizeMb) {
    }

    // What SeekerApplicationController needs to render seeker/applications.html (S-D2):
    // the visible rows (already narrowed to the chosen chip), the effective status filter,
    // the four chip counts as their own fields - always the UNFILTERED totals, so the
    // chips stay stable while the table follows the chosen filter (matching
    // employer/applications.html's own jobId select, Section 6.3 E-D2) - and per-application
    // unread-message counts (Section 6.5.1, employer/applications.html's same Map<Long,Long>
    // pattern).
    public record SeekerActiveApplications(List<JobApplication> applications, String status, long totalActive,
            long appliedCount, long underReviewCount, long shortlistedCount, long interviewCount,
            Map<Long, Long> unreadCounts) {
    }

    // What SeekerApplicationController needs to render seeker/application-history.html
    // (S-D4): the paginated rows (as ready-made display rows, not bare entities), the
    // effective view/result filters (so the form/chips keep whatever was chosen) and the
    // always-unfiltered summary line.
    public record SeekerApplicationHistory(Page<JobApplication> page, List<SeekerApplicationHistoryRow> rows,
            String view, String result, String summary) {
    }
}
