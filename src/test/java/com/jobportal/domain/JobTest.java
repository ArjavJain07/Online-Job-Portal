package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.enums.JobDisplayStatus;
import com.jobportal.domain.enums.JobStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

// Unit tests for Job.isLive and Job.displayStatus (Section 5.5): the only place the "Live"
// rule may be written, per the Foundation contract item 4.
class JobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    private Job job(JobStatus status, LocalDate deadline, boolean employerEnabled) {
        User employer = new User();
        employer.setEnabled(employerEnabled);

        Job job = new Job();
        job.setEmployer(employer);
        job.setStatus(status);
        job.setApplicationDeadline(deadline);
        return job;
    }

    @Test
    void approvedJobWithFutureDeadlineAndEnabledEmployerIsLive() {
        Job job = job(JobStatus.APPROVED, TODAY.plusDays(1), true);
        assertThat(job.isLive(TODAY)).isTrue();
    }

    @Test
    void deadlineOfTodayIsStillLive() {
        Job job = job(JobStatus.APPROVED, TODAY, true);
        assertThat(job.isLive(TODAY)).isTrue();
    }

    @Test
    void deadlineOfYesterdayIsNotLive() {
        Job job = job(JobStatus.APPROVED, TODAY.minusDays(1), true);
        assertThat(job.isLive(TODAY)).isFalse();
    }

    @Test
    void disabledEmployerIsNotLiveEvenWithFutureDeadline() {
        Job job = job(JobStatus.APPROVED, TODAY.plusDays(30), false);
        assertThat(job.isLive(TODAY)).isFalse();
    }

    @Test
    void pendingApprovalIsNeverLive() {
        Job job = job(JobStatus.PENDING_APPROVAL, TODAY.plusDays(30), true);
        assertThat(job.isLive(TODAY)).isFalse();
    }

    @Test
    void rejectedIsNeverLive() {
        Job job = job(JobStatus.REJECTED, TODAY.plusDays(30), true);
        assertThat(job.isLive(TODAY)).isFalse();
    }

    @Test
    void closedIsNeverLive() {
        Job job = job(JobStatus.CLOSED, TODAY.plusDays(30), true);
        assertThat(job.isLive(TODAY)).isFalse();
    }

    @Test
    void displayStatusIsLiveWhenApprovedFutureDeadlineEnabled() {
        Job job = job(JobStatus.APPROVED, TODAY.plusDays(1), true);
        assertThat(job.displayStatus(TODAY)).isEqualTo(JobDisplayStatus.LIVE);
    }

    @Test
    void displayStatusIsExpiredWhenDeadlinePassed() {
        Job job = job(JobStatus.APPROVED, TODAY.minusDays(1), true);
        assertThat(job.displayStatus(TODAY)).isEqualTo(JobDisplayStatus.EXPIRED);
    }

    @Test
    void displayStatusIsHiddenWhenEmployerDisabled() {
        Job job = job(JobStatus.APPROVED, TODAY.plusDays(30), false);
        assertThat(job.displayStatus(TODAY)).isEqualTo(JobDisplayStatus.HIDDEN);
    }

    // Hidden takes precedence over Expired (Job.displayStatus's own doc comment): an
    // expired job at a deactivated employer still reads as Hidden, not Expired.
    @Test
    void hiddenTakesPrecedenceOverExpired() {
        Job job = job(JobStatus.APPROVED, TODAY.minusDays(10), false);
        assertThat(job.displayStatus(TODAY)).isEqualTo(JobDisplayStatus.HIDDEN);
    }

    @Test
    void displayStatusMirrorsPendingRejectedClosed() {
        assertThat(job(JobStatus.PENDING_APPROVAL, TODAY.plusDays(1), true).displayStatus(TODAY))
                .isEqualTo(JobDisplayStatus.PENDING_APPROVAL);
        assertThat(job(JobStatus.REJECTED, TODAY.plusDays(1), true).displayStatus(TODAY))
                .isEqualTo(JobDisplayStatus.REJECTED);
        assertThat(job(JobStatus.CLOSED, TODAY.plusDays(1), true).displayStatus(TODAY))
                .isEqualTo(JobDisplayStatus.CLOSED);
    }

    @Test
    void isActiveIsTrueOnlyWhilePendingOrApproved() {
        assertThat(job(JobStatus.PENDING_APPROVAL, TODAY, true).isActive()).isTrue();
        assertThat(job(JobStatus.APPROVED, TODAY, true).isActive()).isTrue();
        assertThat(job(JobStatus.REJECTED, TODAY, true).isActive()).isFalse();
        assertThat(job(JobStatus.CLOSED, TODAY, true).isActive()).isFalse();
    }

    // skillList() used to split the stored CSV; since skills became an entity (Section
    // 10.8) it reads the Skill relation, so what is worth asserting here is that it keeps
    // the labels and the order assignSkills was given. The CSV parsing it replaced now
    // lives in SkillParser, which SkillParserTest covers on its own - including the
    // trimming and blank-dropping this test used to check.
    @Test
    void skillListReturnsLabelsInTheOrderAssigned() {
        Job job = job(JobStatus.APPROVED, TODAY, true);
        job.assignSkills(List.of(Skill.of("Java"), Skill.of("Spring Boot"), Skill.of("SQL")));
        assertThat(job.skillList()).containsExactly("Java", "Spring Boot", "SQL");
    }

    @Test
    void skillListIsEmptyForAJobWithNoSkills() {
        Job job = job(JobStatus.APPROVED, TODAY, true);
        assertThat(job.skillList()).isEmpty();
        job.assignSkills(List.of());
        assertThat(job.skillList()).isEmpty();
    }

    // The rollback column (Section 10.8) is written by the same call that writes the
    // relation, so a downgrade to the previous release still finds a job's skills where
    // it expects them. Nothing reads it in the running application, which is exactly why
    // it needs a test of its own: without one, a later change could stop maintaining it
    // and no other test in the suite would notice.
    @Test
    void assignSkillsAlsoWritesTheLegacyRollbackColumn() {
        Job job = job(JobStatus.APPROVED, TODAY, true);
        job.assignSkills(List.of(Skill.of("Java"), Skill.of("Spring Boot")));
        assertThat(job.legacySkillsCsv()).isEqualTo("Java, Spring Boot");

        job.assignSkills(List.of());
        assertThat(job.legacySkillsCsv()).isEmpty();
    }
}
