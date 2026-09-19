package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.dto.RecommendedJob;
import com.jobportal.repository.JobRepository;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Service-level recommendation tests (Section 6.4 S-D5, 7.8) against the real Section 13
// seed data - Priya Sharma is the worked example the plan itself uses (7.8 "Worked
// example").
class RecommendationServiceTest extends IntegrationTestBase {

    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private Clock clock;

    // AC-S-D5-1: Priya's list is exactly Spring Boot Intern (34, Strong match) then QA
    // Engineer (22, Good match); Marketing Executive never appears (no skill or category
    // match). The two scores are the plan's own worked examples (Section 7.8).
    @Test
    void priyaGetsSeededRecommendations() {
        Long priyaId = data.userId("priya@demo.local");

        RecommendationResult result = recommendationService.recommend(priyaId, 20);

        assertThat(result.fallback()).isFalse();
        assertThat(result.jobs()).hasSize(2);

        RecommendedJob first = result.jobs().get(0);
        assertThat(first.job().getTitle()).isEqualTo("Spring Boot Intern");
        assertThat(first.label()).isEqualTo("Strong match");
        assertThat(first.score()).isEqualTo(34);
        assertThat(first.reasons()).anyMatch(r -> r.contains("Java") && r.contains("Spring Boot"));

        RecommendedJob second = result.jobs().get(1);
        assertThat(second.job().getTitle()).isEqualTo("QA Engineer");
        assertThat(second.label()).isEqualTo("Good match");
        assertThat(second.score()).isEqualTo(22);

        assertThat(result.jobs()).extracting(rec -> rec.job().getTitle()).doesNotContain("Marketing Executive");
    }

    // AC-S-D5-2: applied jobs (Java Developer, Frontend Developer, Data Analyst) and
    // non-Live jobs (DevOps Engineer pending, Python Backend Developer expired, Warehouse
    // Supervisor hidden) never appear for Priya.
    @Test
    void appliedAndNonLiveJobsExcluded() {
        Long priyaId = data.userId("priya@demo.local");

        List<String> titles = recommendationService.recommend(priyaId, 20).jobs().stream()
                .map(rec -> rec.job().getTitle()).toList();

        assertThat(titles).doesNotContain("Java Developer", "Frontend Developer", "Data Analyst",
                "DevOps Engineer", "Python Backend Developer", "Warehouse Supervisor");
    }

    // AC-S-D5-3 (service half): Neha has no skills and has never applied, so she gets the
    // "Latest jobs" fallback - the 6 newest Live jobs, each labelled "Latest jobs" with no
    // reasons, newest-approved first (Spring Boot Intern was approved most recently, 5 days
    // ago, Section 13.4).
    @Test
    void emptyProfileGetsLatestJobsFallback() {
        Long nehaId = data.userId("neha@demo.local");

        RecommendationResult result = recommendationService.recommend(nehaId, 6);

        assertThat(result.fallback()).isTrue();
        assertThat(result.jobs()).hasSize(6);
        assertThat(result.jobs()).allMatch(rec -> rec.label().equals("Latest jobs"));
        assertThat(result.jobs()).allMatch(rec -> rec.reasons().isEmpty());
        assertThat(result.jobs().get(0).job().getTitle()).isEqualTo("Spring Boot Intern");
    }

    // E8 (Section 7.8, "service-level test"): two qualifying jobs tied on score are broken
    // by approvedAt, most recently approved first. Two throwaway jobs are inserted (rolled
    // back with the rest of the test's transaction) that both score 29 for Priya, one
    // approved 2 days ago and the other 5 days ago.
    @Test
    void tiedScoresOrderByMostRecentlyApprovedJob() {
        Long priyaId = data.userId("priya@demo.local");
        User acme = data.user("hr@acme.local");
        LocalDate today = LocalDate.now(clock);

        Job recent = tiedJob(acme, "Zzz Recommendation Test Recent", today.minusDays(2));
        Job older = tiedJob(acme, "Zzz Recommendation Test Older", today.minusDays(5));
        jobRepository.save(recent);
        jobRepository.save(older);

        List<RecommendedJob> matches = recommendationService.recommend(priyaId, 20).jobs();
        int recentIndex = indexOfTitle(matches, recent.getTitle());
        int olderIndex = indexOfTitle(matches, older.getTitle());

        assertThat(recentIndex).isGreaterThanOrEqualTo(0);
        assertThat(olderIndex).isGreaterThanOrEqualTo(0);
        assertThat(matches.get(recentIndex).score()).isEqualTo(matches.get(olderIndex).score());
        assertThat(recentIndex).isLessThan(olderIndex);
    }

    // A job that scores exactly 29 for Priya (Java skill match +10, Full-time preference
    // +5, Pune location +5, meets experience +4, past-category affinity +3, freshness +2),
    // differing only by how long ago it was approved.
    private Job tiedJob(User employer, String title, LocalDate approvedDate) {
        Job job = new Job();
        job.setEmployer(employer);
        job.setTitle(title);
        job.setDescription("A throwaway job used only to test recommendation tie-breaking.");
        job.setRequirements("None.");
        job.assignSkills(data.skills("Java"));
        job.setCategory(JobCategory.SOFTWARE_DEVELOPMENT); // one of Priya's past categories
        job.setJobType(JobType.FULL_TIME); // Priya's preferred type
        job.setWorkMode(WorkMode.ONSITE);
        job.setLocation("Pune"); // Priya's location
        job.setSalaryMin(500000);
        job.setSalaryMax(700000);
        job.setMinExperienceYears(0); // Priya has 2 years, comfortably meets it
        job.setApplicationDeadline(approvedDate.plusDays(60));
        job.setStatus(JobStatus.APPROVED);
        job.setApprovedAt(approvedDate.atTime(9, 0));
        job.setCreatedAt(LocalDateTime.now(clock));
        return job;
    }

    private int indexOfTitle(List<RecommendedJob> matches, String title) {
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).job().getTitle().equals(title)) {
                return i;
            }
        }
        return -1;
    }
}
