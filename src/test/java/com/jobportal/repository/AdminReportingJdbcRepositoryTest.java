package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobportal.domain.Skill;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.HeadlineCounts;
import com.jobportal.dto.SkillDemandRow;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// The two hand-written SQL reports behind /admin/statistics (Section 10.10), against the
// real Section 13 seed data.
//
// HOW THESE TESTS ARE BUILT, AND WHY
// Hand-written SQL has exactly one failure mode that a smoke test cannot see: it runs,
// returns plausible numbers, and the numbers are wrong - a join that multiplies rows, a
// predicate that drops a boundary row, a filter left off. Every test here is therefore
// DIFFERENTIAL: it asserts the SQL against a second, independent implementation of the
// same question that already existed and is already trusted, namely the JPA repository
// methods and Criteria Specifications this page used before.
//
// That is the reason AdminStatisticsService no longer calling
// countByToStatusAndChangedAtGreaterThanEqual and friends did NOT mean deleting them.
// They are the oracle. A single implementation of a count can only be tested against a
// number somebody typed into a test, which is a test of the typist; two implementations
// that disagree are a bug one of them has, and the seed data is large enough and odd
// enough (a disabled employer, an expired approved job, a closed job) that a wrong join
// has nowhere to hide.
//
// Each report also gets ONE absolute check against the figures Section 13.8 and 7.6
// publish, so that two implementations agreeing on the same wrong answer is caught too.
//
// Nothing here inserts a row. Every case either reads, or repoints a seeded row - the
// seed's own employer/job graph already contains every edge case these queries have to
// get right, and inserting a JobApplication would move H2's identity counter, which does
// not roll back and which JobApplicationTest's hard-coded APP-00016 depends on.
class AdminReportingJdbcRepositoryTest extends IntegrationTestBase {

    @Autowired
    private AdminReportingJdbcRepository reporting;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private ApplicationStatusChangeRepository applicationStatusChangeRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private SkillRepository skillRepository;
    @Autowired
    private Clock clock;

    // Big enough to be no limit at all on this dataset: asking for the WHOLE ranking is
    // what makes the differential test below exhaustive rather than a check of the ten
    // rows the page happens to show.
    private static final int NO_LIMIT = 1000;

    // ---------------------------------------------------------------------------
    // Report 1: headlineCounts
    // ---------------------------------------------------------------------------

    // The whole point of the statement: all twelve counts, in one round-trip, equal to the
    // nine separate JPA counts and three list sizes they replaced. This is the test most
    // likely to catch a bad predicate - the only wrong SQL that survives it is SQL that is
    // wrong in exactly the same way JPQL and Criteria are, in twelve places at once.
    @Test
    void headlineCountsAgreeWithTheJpaCountsTheyReplaced() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = rangeStart(today, 30);
        LocalDateTime activeSince = LocalDateTime.now(clock).minusDays(7);

        HeadlineCounts counts = reporting.headlineCounts(from, activeSince, today);

        // The three that used to be a list's .size() (AC-A-D4-1).
        assertThat(counts.newUsers()).isEqualTo(userRepository.findCreatedAtSince(from).size());
        assertThat(counts.jobsPosted()).isEqualTo(jobRepository.findCreatedAtSince(from).size());
        assertThat(counts.applications()).isEqualTo(jobApplicationRepository.findAppliedAtSince(from).size());

        // The nine that were a `select count(*)` round-trip each.
        assertThat(counts.hires()).isEqualTo(applicationStatusChangeRepository
                .countByToStatusAndChangedAtGreaterThanEqual(ApplicationStatus.HIRED, from));
        assertThat(counts.activeUsers7Days()).isEqualTo(userRepository.countByLastLoginAtGreaterThanEqual(activeSince));
        assertThat(counts.liveJobs()).isEqualTo(jobRepository.count(JobSpecifications.live(today)));
        assertThat(counts.totalUsers()).isEqualTo(userRepository.count());
        assertThat(counts.enabledSeekers()).isEqualTo(userRepository.countByRoleAndEnabled(Role.JOB_SEEKER, true));
        assertThat(counts.applyingSeekers()).isEqualTo(jobApplicationRepository.countDistinctSeekersSince(from));
        assertThat(counts.allEmployers()).isEqualTo(userRepository.countByRole(Role.EMPLOYER));
        assertThat(counts.postingEmployers()).isEqualTo(jobRepository.countDistinctEmployersSince(from));
        assertThat(counts.messagesSent()).isEqualTo(messageRepository.countBySentAtGreaterThanEqual(from));
    }

    // The absolute check, so that "both implementations agree" cannot mean "both are
    // wrong in the same way". Every number here is published in Section 13.8 or in
    // Section 7.6's seeded engagement values, except hires - the two seeded HIRED
    // transitions are 4 and 8 days ago (13.5), so both are inside a 30 day window.
    @Test
    void headlineCountsMatchSection13() {
        LocalDate today = LocalDate.now(clock);

        HeadlineCounts counts = reporting.headlineCounts(
                rangeStart(today, 30), LocalDateTime.now(clock).minusDays(7), today);

        assertThat(counts.applications()).isEqualTo(13);      // 13.8: applications in last 30 days
        assertThat(counts.applyingSeekers()).isEqualTo(4);    // 13.8: from 4 seekers
        assertThat(counts.hires()).isEqualTo(2);
        assertThat(counts.activeUsers7Days()).isEqualTo(6);   // 13.8: active users (7 days)
        assertThat(counts.liveJobs()).isEqualTo(6);           // 13.8: jobs by display status, Live 6
        assertThat(counts.totalUsers()).isEqualTo(10);        // 13.8: 1 admin + 3 employers + 6 seekers
        assertThat(counts.enabledSeekers()).isEqualTo(6);     // 7.6: "4 of 6 (67%)"
        assertThat(counts.allEmployers()).isEqualTo(3);       // 7.6: "3 of 3 (100%)"
        assertThat(counts.postingEmployers()).isEqualTo(3);
        assertThat(counts.messagesSent()).isEqualTo(8);       // 13.8: 8 messages, all in range
    }

    // A narrower window has to move the counts that are scoped to it and leave the three
    // all-time denominators alone. Without this, every `>= :from` in the statement could
    // be pointing at the wrong column - or at no column - and the 30 day test above would
    // still pass, because on this dataset most rows are inside 30 days anyway.
    @Test
    void headlineCountsNarrowWithTheRange() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime activeSince = LocalDateTime.now(clock).minusDays(7);

        HeadlineCounts wide = reporting.headlineCounts(rangeStart(today, 90), activeSince, today);
        HeadlineCounts narrow = reporting.headlineCounts(rangeStart(today, 7), activeSince, today);

        assertThat(wide.applications()).isEqualTo(15);   // 13.8: 15 applications in total
        assertThat(narrow.applications()).isLessThan(wide.applications());
        assertThat(narrow.hires()).isEqualTo(1);         // only the 4-days-ago HIRED transition

        // Not scoped to the range, by design (see HeadlineCounts): a participation rate
        // measures the range's activity against the whole population.
        assertThat(narrow.totalUsers()).isEqualTo(wide.totalUsers());
        assertThat(narrow.enabledSeekers()).isEqualTo(wide.enabledSeekers());
        assertThat(narrow.allEmployers()).isEqualTo(wide.allEmployers());
        assertThat(narrow.activeUsers7Days()).isEqualTo(wide.activeUsers7Days());
        assertThat(narrow.liveJobs()).isEqualTo(wide.liveJobs());
    }

    // ---------------------------------------------------------------------------
    // Report 2: topSkillsByLiveJobDemand
    // ---------------------------------------------------------------------------

    // The differential test for the ranking, and the one that would catch a bad join: for
    // EVERY skill in the vocabulary - not only the ones the report returned - the number
    // the SQL reports must equal what the existing Criteria path says when asked
    // "live(today) and hasSkill(slug)". A skill the report leaves out must have no Live
    // job at all, which is what makes a dropped row a failure rather than an absence.
    @Test
    void skillDemandAgreesWithTheLiveJobSpecification() {
        LocalDate today = LocalDate.now(clock);

        Map<String, Long> reported = new HashMap<>();
        for (SkillDemandRow row : reporting.topSkillsByLiveJobDemand(today, NO_LIMIT)) {
            reported.put(row.label(), row.liveJobs());
        }

        for (Skill skill : skillRepository.findAll()) {
            long viaSpecification = jobRepository.count(
                    JobSpecifications.live(today).and(JobSpecifications.hasSkill(skill.getSlug())));
            assertThat(reported.getOrDefault(skill.getLabel(), 0L))
                    .as("live job demand for skill '%s' (slug '%s')", skill.getLabel(), skill.getSlug())
                    .isEqualTo(viaSpecification);
        }
    }

    // The absolute check on the ranking. The 6 Live jobs of Section 13.4 ask for 20
    // (job, skill) pairs over 16 distinct skills, and only three skills are asked for by
    // more than one Live job: Java by the Java Developer, Spring Boot Intern and QA
    // Engineer roles, SQL by Java Developer and Data Analyst, Spring Boot by Java
    // Developer and Spring Boot Intern.
    //
    // The two skills on 2 are asserted as a pair rather than in order, deliberately: the
    // query breaks ties by skills.id, so their relative order is the order they entered
    // the vocabulary, and pinning it here would turn a harmless change to the seeder into
    // a failing test in another file. What must hold is that both are there with 2.
    @Test
    void skillDemandRanksTheSeededLiveJobs() {
        List<SkillDemandRow> ranking = reporting.topSkillsByLiveJobDemand(LocalDate.now(clock), NO_LIMIT);

        assertThat(ranking).hasSize(16);
        assertThat(ranking.stream().mapToLong(SkillDemandRow::liveJobs).sum()).isEqualTo(20);

        assertThat(ranking.get(0)).isEqualTo(new SkillDemandRow("Java", 3));
        assertThat(ranking.subList(1, 3)).containsExactlyInAnyOrder(
                new SkillDemandRow("SQL", 2), new SkillDemandRow("Spring Boot", 2));
        assertThat(ranking.subList(3, 16)).allMatch(row -> row.liveJobs() == 1);

        // Skills only a non-Live job asks for are absent, not present with a 0: Docker,
        // AWS and Linux belong to the still-pending DevOps Engineer job, Retail and Team
        // Management to the rejected Store Manager, CRM to the closed Customer Support
        // Associate, Django to the expired Python Backend Developer, and Logistics and
        // Inventory to the Warehouse Supervisor job of the disabled QuickHire employer.
        assertThat(ranking).extracting(SkillDemandRow::label).doesNotContain(
                "Docker", "AWS", "Linux", "Retail", "Team Management", "CRM", "Django", "Logistics", "Inventory");
    }

    // The employer-enabled third of the Live definition is the easiest one to leave out of
    // a hand-written join, and leaving it out is invisible on a casual read of the output.
    // Disabling Acme must remove its 4 Live jobs from both reports at once - which also
    // demonstrates the one real consequence of Spring JDBC sharing the transaction with
    // Hibernate: the change has to be FLUSHED to the database before SQL can see it.
    // saveAndFlush, not save, for exactly that reason.
    @Test
    void disablingAnEmployerRemovesItsJobsFromBothReports() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = rangeStart(today, 30);
        LocalDateTime activeSince = LocalDateTime.now(clock).minusDays(7);

        User acme = data.user("hr@acme.local");
        acme.setEnabled(false);
        userRepository.saveAndFlush(acme);

        // Acme had 4 of the 6 Live jobs (13.8); Globex's Data Analyst and Marketing
        // Executive are the two that remain.
        assertThat(reporting.headlineCounts(from, activeSince, today).liveJobs()).isEqualTo(2);
        assertThat(reporting.headlineCounts(from, activeSince, today).liveJobs())
                .isEqualTo(jobRepository.count(JobSpecifications.live(today)));

        List<SkillDemandRow> ranking = reporting.topSkillsByLiveJobDemand(today, NO_LIMIT);
        assertThat(ranking).containsExactlyInAnyOrder(
                new SkillDemandRow("SQL", 1), new SkillDemandRow("Excel", 1), new SkillDemandRow("Python", 1),
                new SkillDemandRow("Power BI", 1), new SkillDemandRow("SEO", 1),
                new SkillDemandRow("Social Media", 1), new SkillDemandRow("Content Writing", 1));
        assertThat(ranking).extracting(SkillDemandRow::label).doesNotContain("Java", "Spring Boot");
    }

    // The row limit is a literal formatted into the SQL rather than a bound parameter
    // (FETCH FIRST takes no portable parameter), so it gets its own test: it must cut the
    // ranking from the bottom, keep it in rank order, and refuse a value that would make
    // an empty report look like an honest one.
    //
    // The exact exception type is asserted, not just that it throws: this is what caught
    // the class being annotated @Repository, which silently re-wrapped the guard as
    // InvalidDataAccessApiUsageException - a data-access failure for a call that had not
    // reached the database. See the repository's header.
    @Test
    void skillDemandHonoursTheRowLimit() {
        LocalDate today = LocalDate.now(clock);

        List<SkillDemandRow> topThree = reporting.topSkillsByLiveJobDemand(today, 3);
        assertThat(topThree).hasSize(3);
        assertThat(topThree).isEqualTo(reporting.topSkillsByLiveJobDemand(today, NO_LIMIT).subList(0, 3));
        assertThat(topThree).extracting(SkillDemandRow::liveJobs).containsExactly(3L, 2L, 2L);

        assertThat(reporting.topSkillsByLiveJobDemand(today, 1))
                .containsExactly(new SkillDemandRow("Java", 3));

        assertThatThrownBy(() -> reporting.topSkillsByLiveJobDemand(today, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be at least 1");
    }

    // Mirrors AdminStatisticsService.rangeStart, which is private there - the same
    // repetition that service makes of DateBuckets' own rule, and for the same reason: the
    // boundary has to be the one the page uses or this whole file is testing a different
    // question (Section 7.6).
    private LocalDateTime rangeStart(LocalDate today, int days) {
        LocalDate firstDay = days == 90 ? today.minusDays(90) : today.minusDays(days - 1L);
        return firstDay.atStartOfDay();
    }
}
