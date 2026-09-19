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

    // The search index columns (Section 10.9, V7) are written by the same setters that
    // write the text they describe, and for the same reason the rollback column above has
    // a test: nothing in a unit test reads them, so without this the setters could quietly
    // stop maintaining them and the only symptom would be a production search that misses
    // every job edited since.
    //
    // The stored form is SearchIndex.of(text) - normalised, then padded with one space at
    // each end - which is what lets a single "% word %" LIKE mean "contains this whole
    // word" with no special case for the first or last word in the value.
    @Test
    void settingTitleAndDescriptionWritesTheSearchIndex() {
        Job job = job(JobStatus.APPROVED, TODAY, true);

        job.setTitle("Senior Node.js Developer");
        job.setDescription("Build  APIs, ship them.");

        assertThat(job.searchTitleIndex()).isEqualTo(" senior node js developer ");
        assertThat(job.searchDescriptionIndex()).isEqualTo(" build apis ship them ");
        assertThat(job.needsSearchIndex()).isFalse();
    }

    // An edit has to move the index with it. This is the case @PreUpdate would have got
    // wrong (Hibernate decides which properties are dirty before invoking it), and it is
    // why the assignment lives in the setter.
    @Test
    void editingTheTitleRebuildsItsSearchIndex() {
        Job job = job(JobStatus.APPROVED, TODAY, true);
        job.setTitle("Java Developer");
        assertThat(job.searchTitleIndex()).isEqualTo(" java developer ");

        job.setTitle("Python Developer");
        assertThat(job.searchTitleIndex()).isEqualTo(" python developer ");
    }

    // A job that has never been through the setters carries SearchIndex.UNINDEXED, which is
    // what V7 puts in the column and what SearchIndexService looks for. It has to be
    // distinguishable from a genuinely empty index (" ", from a title of pure punctuation),
    // or the backfill would rewrite that row on every start-up for ever.
    @Test
    void anUnindexedJobIsTellableFromAJobWhoseTextNormalisesToNothing() {
        assertThat(new Job().needsSearchIndex()).isTrue();

        Job job = job(JobStatus.APPROVED, TODAY, true);
        job.setTitle("---");
        job.setDescription("!!!");
        assertThat(job.searchTitleIndex()).isEqualTo("  ");
        assertThat(job.needsSearchIndex()).isFalse();
    }
}
