package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Automatic housekeeping for two Job fields that, before this class, were never acted on:
// applicationDeadline was filtering-only (JobSpecifications.java deadlineOnOrAfter/
// deadlineBefore, JobRepository.countDistinctEmployersWithLiveJobs), so an expired job
// stayed APPROVED forever and still showed on the employer's own /employer/jobs page;
// openings was stored but explicitly excluded from JobService's re-approval check
// (JobService.contentFieldsChanged) and otherwise never read at all.
//
// D-3 (1.6 design decision log) rejected a *stored* EXPIRED status plus a nightly
// scheduler - that decision is about the LIVE/EXPIRED/HIDDEN label, which is still
// computed by Job.displayStatus(today) exactly as before and is completely unchanged
// here. This class does something narrower and later: once a job's deadline has
// genuinely passed, or its openings are genuinely filled, it performs the same CLOSED
// transition an employer would otherwise have had to click "Close" for themselves,
// through the exact same JobService.recordStatusChange path every other closure already
// uses (11.3 contract item 6), so the JobStatusChange row and the JOB_CLOSED activity
// entry are indistinguishable in shape from a manual close - only the actor ("System",
// the existing convention for the one other system-driven transition, JobService.create's
// auto-approval branch) and the reason text say it was automatic.
//
// JobSweepScheduler is the only production caller; JobSweepServiceTest is the only other
// one, calling sweep() directly with the fixed test Clock instead of waiting on a timer
// (Section 7.11 explains the split).
@Service
public class JobSweepService {

    private static final Logger log = LoggerFactory.getLogger(JobSweepService.class);

    public static final String DEADLINE_REASON = "Automatically closed: the application deadline passed.";
    public static final String OPENINGS_REASON = "Automatically closed: all openings were filled.";

    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobService jobService;
    private final Clock clock;

    public JobSweepService(JobRepository jobRepository, JobApplicationRepository jobApplicationRepository,
            JobService jobService, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.jobService = jobService;
        this.clock = clock;
    }

    // The sweep itself. Idempotent and safe to call as often as you like: only APPROVED
    // jobs are candidates, and closing one goes through recordStatusChange, which is the
    // only place that ever writes job.status - so a second call in the same instant finds
    // every job it already closed sitting in CLOSED, not APPROVED, and does nothing more
    // to it. Every other status (PENDING_APPROVAL, REJECTED, CLOSED) is left alone
    // whatever its deadline or openings say: closing a CLOSED job is meaningless, and
    // deciding a PENDING_APPROVAL or REJECTED job's fate is an admin/employer decision
    // (Section 5.5), not an expiry this sweep is entitled to act on.
    //
    // One query loads every currently APPROVED job - small at this app's scale, the same
    // assumption JobService.employerJobs already makes ("bounded by
    // maxActiveJobsPerEmployer, at most 100" per employer) - and each is then checked in
    // Java against both rules. A job that matches both (deadline passed AND openings
    // filled) is closed once, for the deadline: the two reasons are deliberately
    // mutually exclusive per job so its timeline never shows two "Closed" rows for what
    // is, from the employer's side, a single event.
    @Transactional
    public SweepResult sweep() {
        LocalDate today = LocalDate.now(clock);
        List<Job> liveJobs = jobRepository.findAll(JobSpecifications.hasStatus(JobStatus.APPROVED));

        int closedByDeadline = 0;
        int closedByOpenings = 0;
        for (Job job : liveJobs) {
            if (job.getApplicationDeadline().isBefore(today)) {
                close(job, DEADLINE_REASON, "deadline passed");
                closedByDeadline++;
            } else if (hiredCount(job) >= job.getOpenings()) {
                close(job, OPENINGS_REASON, "openings filled");
                closedByOpenings++;
            }
        }

        if (closedByDeadline > 0 || closedByOpenings > 0) {
            log.info("Job sweep closed {} job(s): {} by deadline, {} by openings filled",
                    closedByDeadline + closedByOpenings, closedByDeadline, closedByOpenings);
        }
        return new SweepResult(closedByDeadline, closedByOpenings);
    }

    private long hiredCount(Job job) {
        return jobApplicationRepository.countByJob_IdAndStatus(job.getId(), ApplicationStatus.HIRED);
    }

    // Same shape as JobService.close(), but with no employer in the loop: actor null is
    // the established "System" convention (JobService.create's auto-approval branch,
    // Section 5.5 "(new) -> APPROVED, System"; JobService.actorDisplayName turns it into
    // "System" on the JobStatusChange row). reason is shown as the timeline note exactly
    // like an admin's rejection or take-down reason (fragments/timeline :: jobTimeline),
    // which is what makes this read correctly on the employer's posting-history page.
    private void close(Job job, String reason, String descriptionSuffix) {
        job.setClosedAt(LocalDateTime.now(clock));
        String description = "System closed " + job.getTitle() + " (" + descriptionSuffix + ")";
        jobService.recordStatusChange(job, JobStatus.CLOSED, reason, null, ActivityType.JOB_CLOSED, description);
    }

    // How many jobs this run closed, split by reason so JobSweepServiceTest and the log
    // line above can tell them apart without re-querying.
    public record SweepResult(int closedByDeadline, int closedByOpenings) {
        public int totalClosed() {
            return closedByDeadline + closedByOpenings;
        }
    }
}
