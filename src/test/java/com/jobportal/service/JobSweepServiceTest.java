package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.service.JobSweepService.SweepResult;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Service-level tests for the automatic close sweep: the gap noted against
// JobSpecifications/JobRepository (Job.applicationDeadline was filtering-only) and
// JobService (Job.openings was stored but never acted on, deliberately excluded from
// contentFieldsChanged). #sweepAgainstSeedData exercises the real Section 13 dataset,
// the same convention RecommendationServiceTest uses; the rest build throwaway jobs
// (also RecommendationServiceTest's own pattern, see its tiedJob()) for precise control
// over the boundary, idempotency and non-disturbance of other statuses.
//
// Every test here calls JobSweepService#sweep() directly - no @Scheduled trigger, no
// timer, no wait (see JobSweepScheduler and JobSweepSchedulerTest for that half).
class JobSweepServiceTest extends IntegrationTestBase {

    @Autowired
    private JobSweepService jobSweepService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private JobStatusChangeRepository jobStatusChangeRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private Clock clock;

    // Against the real seed data (13.4, 13.5), at the fixed clock's "today" (16 Sep
    // 2026) exactly two Live jobs already qualify: Python Backend Developer's deadline
    // passed 2 days ago (J11), and Frontend Developer's one opening was filled when
    // Sneha Iyer was hired (J3, A6). Every other Live job (Java Developer, Spring Boot
    // Intern, QA Engineer, Data Analyst, Marketing Executive) has neither problem and
    // must stay untouched, and the already-CLOSED Customer Support Associate - which
    // also has a past deadline AND a filled opening (J10, A12) - must stay exactly as it
    // was: proof that "other statuses are never disturbed" holds even for a job that
    // would otherwise qualify twice over.
    @Test
    void sweepAgainstSeedData() {
        Job expiredJob = data.job("Python Backend Developer");
        Job filledJob = data.job("Frontend Developer");
        Job alreadyClosed = data.job("Customer Support Associate");
        assertThat(expiredJob.getStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(filledJob.getStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(alreadyClosed.getStatus()).isEqualTo(JobStatus.CLOSED);
        List<JobStatusChange> closedHistoryBefore =
                jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(alreadyClosed.getId());

        SweepResult result = jobSweepService.sweep();

        assertThat(result.closedByDeadline()).isEqualTo(1);
        assertThat(result.closedByOpenings()).isEqualTo(1);
        assertThat(jobRepository.findById(expiredJob.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(jobRepository.findById(filledJob.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);

        for (String stillLive : List.of("Java Developer", "Spring Boot Intern", "QA Engineer", "Data Analyst",
                "Marketing Executive")) {
            assertThat(jobRepository.findById(data.jobId(stillLive)).orElseThrow().getStatus())
                    .as(stillLive).isEqualTo(JobStatus.APPROVED);
        }

        List<JobStatusChange> closedHistoryAfter =
                jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(alreadyClosed.getId());
        assertThat(closedHistoryAfter).hasSameSizeAs(closedHistoryBefore);
    }

    // Closing writes a JobStatusChange the employer's posting-history timeline
    // (fragments/timeline :: jobTimeline) renders exactly like a manual close - "Closed"
    // badge (getTimelineLabel), "by System", and the reason as its note - plus the
    // matching JOB_CLOSED activity entry (11.3 contract item 5).
    @Test
    void closesJobPastDeadlineAndRecordsReasonActorAndActivity() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Deadline Test", LocalDate.now(clock).minusDays(1), 1);
        long activityBefore = activityLogRepository.count();

        SweepResult result = jobSweepService.sweep();

        assertThat(result.closedByDeadline()).isGreaterThanOrEqualTo(1);
        Job closed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();

        List<JobStatusChange> history = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId());
        assertThat(history).hasSize(1);
        JobStatusChange change = history.get(0);
        assertThat(change.getFromStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(change.getToStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(change.getTimelineLabel()).isEqualTo("Closed");
        assertThat(change.getActorName()).isEqualTo("System");
        assertThat(change.getActorRole()).isNull();
        assertThat(change.getReason()).isEqualTo(JobSweepService.DEADLINE_REASON);

        assertThat(activityLogRepository.count()).isGreaterThan(activityBefore);
        ActivityLog entry = activityLogRepository.findAll().stream()
                .filter(a -> a.getType() == ActivityType.JOB_CLOSED && job.getId().equals(a.getTargetId()))
                .findFirst().orElseThrow();
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getTargetType()).isEqualTo(TargetType.JOB);
        assertThat(entry.getDescription()).contains(job.getTitle()).contains("deadline passed");
    }

    // A deadline of exactly today is still Live (Job.isLive/displayStatus both use
    // applicationDeadline.isBefore(today), never isBefore-or-equal, and JobTest already
    // checks that boundary for those two) - the sweep must leave it alone too.
    @Test
    void deadlineEqualToTodayIsNotClosed() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Boundary Test", LocalDate.now(clock), 1);

        jobSweepService.sweep();

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.APPROVED);
        assertThat(jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId())).isEmpty();
    }

    // openings = 2, two HIRED applications from different seekers (plus one merely
    // APPLIED, to prove only HIRED counts towards openings) closes the job.
    @Test
    void closesJobWhenHiredCountReachesOpenings() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Openings Test", LocalDate.now(clock).plusDays(30), 2);
        repoint(data.application("priya@demo.local", "Java Developer"), job, ApplicationStatus.HIRED);
        repoint(data.application("arjun@demo.local", "Java Developer"), job, ApplicationStatus.HIRED);
        repoint(data.application("rohan@demo.local", "Java Developer"), job, ApplicationStatus.APPLIED);

        SweepResult result = jobSweepService.sweep();

        assertThat(result.closedByOpenings()).isGreaterThanOrEqualTo(1);
        Job closed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        JobStatusChange change = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId()).get(0);
        assertThat(change.getReason()).isEqualTo(JobSweepService.OPENINGS_REASON);
    }

    // 1 of 2 openings filled stays open: the sweep must not close on a partial fill.
    @Test
    void openingsNotYetFilledStaysApproved() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Partial Openings Test", LocalDate.now(clock).plusDays(30), 2);
        repoint(data.application("priya@demo.local", "Data Analyst"), job, ApplicationStatus.HIRED);

        jobSweepService.sweep();

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.APPROVED);
    }

    // When both rules match the same job, exactly one JobStatusChange row is written,
    // for the deadline - see the comment on JobSweepService#sweep for why the two
    // reasons are mutually exclusive per job.
    @Test
    void deadlineReasonWinsWhenBothConditionsAreTrue() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Both Conditions Test", LocalDate.now(clock).minusDays(1), 1);
        repoint(data.application("sneha@demo.local", "Marketing Executive"), job, ApplicationStatus.HIRED);

        jobSweepService.sweep();

        List<JobStatusChange> history = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getReason()).isEqualTo(JobSweepService.DEADLINE_REASON);
    }

    // Closing is idempotent: a second sweep must not add a second JobStatusChange row or
    // otherwise touch a job the first sweep already closed.
    @Test
    void secondSweepMakesNoFurtherChangeToAnAlreadyClosedJob() {
        User acme = data.user("hr@acme.local");
        Job job = newJob(acme, "Zzz Sweep Idempotency Test", LocalDate.now(clock).minusDays(1), 1);

        jobSweepService.sweep();
        int historyAfterFirstRun = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId()).size();
        jobSweepService.sweep();
        int historyAfterSecondRun = jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(job.getId()).size();

        assertThat(historyAfterFirstRun).isEqualTo(1);
        assertThat(historyAfterSecondRun).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    // PENDING_APPROVAL, REJECTED and CLOSED jobs are never touched by this sweep, even
    // when their deadline has passed or their openings are filled: deciding those is an
    // admin/employer action (5.5), not an expiry this sweep may act on.
    @Test
    void otherStatusesNeverTouchedEvenWhenTheyWouldOtherwiseQualify() {
        User globex = data.user("talent@globex.local");
        LocalDate pastDeadline = LocalDate.now(clock).minusDays(1);
        LocalDate futureDeadline = LocalDate.now(clock).plusDays(30);

        Job pending = newJob(globex, "Zzz Sweep Pending Test", pastDeadline, 1, JobStatus.PENDING_APPROVAL);

        Job rejected = newJob(globex, "Zzz Sweep Rejected Test", futureDeadline, 1, JobStatus.REJECTED);
        repoint(data.application("arjun@demo.local", "Marketing Executive"), rejected, ApplicationStatus.HIRED);

        Job closed = newJob(globex, "Zzz Sweep Closed Test", pastDeadline, 1, JobStatus.CLOSED);
        repoint(data.application("rohan@demo.local", "Data Analyst"), closed, ApplicationStatus.HIRED);

        jobSweepService.sweep();

        assertThat(jobRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PENDING_APPROVAL);
        assertThat(jobRepository.findById(rejected.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(jobRepository.findById(closed.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(pending.getId())).isEmpty();
        assertThat(jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(rejected.getId())).isEmpty();
        assertThat(jobStatusChangeRepository.findByJob_IdOrderByChangedAtAsc(closed.getId())).isEmpty();
    }

    private Job newJob(User employer, String title, LocalDate deadline, int openings) {
        return newJob(employer, title, deadline, openings, JobStatus.APPROVED);
    }

    // A throwaway job saved directly in the target status (RecommendationServiceTest's
    // own tiedJob() pattern), rolled back with the rest of the test's transaction.
    private Job newJob(User employer, String title, LocalDate deadline, int openings, JobStatus status) {
        Job job = new Job();
        job.setEmployer(employer);
        job.setTitle(title);
        job.setDescription("A throwaway job used only by JobSweepServiceTest.");
        job.setRequirements("None.");
        job.assignSkills(data.skills("Java"));
        job.setCategory(JobCategory.SOFTWARE_DEVELOPMENT);
        job.setJobType(JobType.FULL_TIME);
        job.setWorkMode(WorkMode.ONSITE);
        job.setLocation("Pune");
        job.setSalaryMin(500000);
        job.setSalaryMax(700000);
        job.setMinExperienceYears(0);
        job.setOpenings(openings);
        job.setApplicationDeadline(deadline);
        job.setStatus(status);
        if (status == JobStatus.APPROVED || status == JobStatus.CLOSED) {
            job.setApprovedAt(LocalDateTime.now(clock).minusDays(2));
        }
        if (status == JobStatus.CLOSED) {
            job.setClosedAt(LocalDateTime.now(clock).minusDays(1));
        }
        job.setCreatedAt(LocalDateTime.now(clock).minusDays(3));
        return jobRepository.save(job);
    }

    // Repoints an EXISTING seeded application at a throwaway job instead of inserting a
    // new JobApplication row. Deliberate: job_applications uses GenerationType.IDENTITY,
    // and rolling back a transaction does not rewind H2's identity counter (it is not
    // transactional, same as a real database) - a test that INSERTs a new application
    // here, even one that rolls back, permanently shifts the id every later application
    // in the whole test run receives. JobApplicationTest#validApplicationShowsConfirmationReference
    // hardcodes the very next one as APP-00016 (the 16th row since the 15 seeded ones,
    // Section 13.5), so this suite must only ever UPDATE existing rows, never add one.
    // Picking three different seekers per job keeps uk_application_job_seeker happy.
    private void repoint(JobApplication application, Job job, ApplicationStatus status) {
        application.setJob(job);
        application.setStatus(status);
        jobApplicationRepository.save(application);
    }
}
