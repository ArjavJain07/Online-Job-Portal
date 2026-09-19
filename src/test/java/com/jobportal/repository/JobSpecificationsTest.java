package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Integration test for the JobSpecifications building blocks (Section 12.2), run against
// the real Section 13 seed data through JobRepository (JpaSpecificationExecutor) instead
// of a hand-built @DataJpaTest fixture, so the "live excludes ..." case can be checked
// against the exact jobs the acceptance criteria talk about.
class JobSpecificationsTest extends IntegrationTestBase {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private Clock clock;

    // 11.3 contract item 4: live(today) must match Job.isLive(today) exactly. The seed
    // data (13.4, 13.8) has exactly 6 Live jobs; every other stored status - pending,
    // rejected, closed - and every computed state - expired, hidden (disabled employer) -
    // must be excluded.
    @Test
    void liveExcludesPendingRejectedClosedExpiredAndHidden() {
        List<Job> liveJobs = jobRepository.findAll(JobSpecifications.live(LocalDate.now(clock)));

        assertThat(liveJobs).extracting(Job::getTitle).containsExactlyInAnyOrder(
                "Java Developer", "Spring Boot Intern", "Frontend Developer", "QA Engineer", "Data Analyst",
                "Marketing Executive");
    }

    // notAppliedBy alone, with no live() filter: a seeker with no applications (Karan
    // Singh) excludes nothing; Priya's 4 applications (Java Developer, Frontend
    // Developer, Data Analyst, Python Backend Developer - Section 13.5) are excluded from
    // every job, whether or not they are still Live.
    @Test
    void notAppliedByExcludesEveryJobTheSeekerHasAppliedTo() {
        Long karanId = data.userId("karan@demo.local");
        List<Job> forKaran = jobRepository.findAll(JobSpecifications.notAppliedBy(karanId));
        assertThat(forKaran).hasSize(12);

        Long priyaId = data.userId("priya@demo.local");
        List<Job> forPriya = jobRepository.findAll(JobSpecifications.notAppliedBy(priyaId));
        assertThat(forPriya).hasSize(8);
        assertThat(forPriya).extracting(Job::getTitle).doesNotContain(
                "Java Developer", "Frontend Developer", "Data Analyst", "Python Backend Developer");
    }

    // Combined with live(): of Priya's 3 Live applications, only the 3 Live jobs she has
    // not applied to remain (7.8 recommendation candidates use this same combination).
    @Test
    void liveAndNotAppliedByCombineForRecommendationCandidates() {
        Long priyaId = data.userId("priya@demo.local");
        List<Job> candidates = jobRepository.findAll(
                JobSpecifications.live(LocalDate.now(clock)).and(JobSpecifications.notAppliedBy(priyaId)));

        assertThat(candidates).extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Spring Boot Intern", "QA Engineer", "Marketing Executive");
    }

    // keyword() must escape LIKE metacharacters (Section 7.9) before it reaches
    // JobSpecifications.likePattern: without escaping, "%" and "_" are SQL wildcards that
    // would match almost every seeded job (an empty/one-character LIKE wildcard), so
    // matching only the one job that really contains the literal character proves the
    // escaping works.
    @Test
    void keywordEscapesPercentAndUnderscore() {
        Job job = jobRepository.saveAndFlush(oddlyNamedJob());

        List<Job> percentMatches = jobRepository.findAll(JobSpecifications.keyword("%"));
        assertThat(percentMatches).extracting(Job::getId).containsExactly(job.getId());

        List<Job> underscoreMatches = jobRepository.findAll(JobSpecifications.keyword("_"));
        assertThat(underscoreMatches).extracting(Job::getId).containsExactly(job.getId());
    }

    // A job whose title literally contains "%" and "_" - no seeded job does, so any match
    // on these characters must come from this one job.
    private Job oddlyNamedJob() {
        Job job = new Job();
        job.setEmployer(data.user("hr@acme.local"));
        job.setTitle("50% Off_Sale Associate");
        job.setDescription("Seasonal sales role.");
        job.setRequirements("Retail experience preferred.");
        job.assignSkills(data.skills("Sales"));
        job.setCategory(JobCategory.SALES);
        job.setJobType(JobType.CONTRACT);
        job.setWorkMode(WorkMode.ONSITE);
        job.setLocation("Pune");
        job.setSalaryMin(200_000);
        job.setSalaryMax(300_000);
        job.setMinExperienceYears(0);
        job.setOpenings(1);
        job.setApplicationDeadline(LocalDate.now(clock).plusDays(30));
        job.setStatus(JobStatus.APPROVED);
        job.setCreatedAt(LocalDateTime.now(clock));
        return job;
    }
}
