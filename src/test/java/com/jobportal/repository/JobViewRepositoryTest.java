package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobView;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.WorkMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

// Repository-slice test for JobViewRepository (dated view analytics feature, Section 6.3
// E-D5, 7.6): findViewedAtSince must scope to one employer, optionally narrow to one job,
// and respect the "since" cutoff - the same three axes JobApplicationRepository
// #findAppliedAtSince is already trusted for, since EmployerStatisticsService buckets both
// through the same DateBuckets.count call.
@DataJpaTest
class JobViewRepositoryTest {

    @Autowired
    private JobViewRepository jobViewRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void scopedToEmployerAndExcludesOtherEmployersJobs() {
        User acme = entityManager.persistAndFlush(employer("acme@example.com", "Acme"));
        User globex = entityManager.persistAndFlush(employer("globex@example.com", "Globex"));
        Job acmeJob = entityManager.persistAndFlush(job(acme, "Java Developer"));
        Job globexJob = entityManager.persistAndFlush(job(globex, "Data Analyst"));

        LocalDateTime now = LocalDateTime.now();
        entityManager.persistAndFlush(view(acmeJob, now.minusDays(1)));
        entityManager.persistAndFlush(view(globexJob, now.minusDays(1)));

        List<LocalDateTime> acmeViews = jobViewRepository.findViewedAtSince(acme.getId(), null, now.minusDays(7));
        assertThat(acmeViews).hasSize(1);
    }

    @Test
    void jobIdNarrowsToOneJobWhenGiven() {
        User acme = entityManager.persistAndFlush(employer("acme@example.com", "Acme"));
        Job javaJob = entityManager.persistAndFlush(job(acme, "Java Developer"));
        Job frontendJob = entityManager.persistAndFlush(job(acme, "Frontend Developer"));

        LocalDateTime now = LocalDateTime.now();
        entityManager.persistAndFlush(view(javaJob, now.minusDays(1)));
        entityManager.persistAndFlush(view(javaJob, now.minusDays(2)));
        entityManager.persistAndFlush(view(frontendJob, now.minusDays(1)));

        List<LocalDateTime> javaViews =
                jobViewRepository.findViewedAtSince(acme.getId(), javaJob.getId(), now.minusDays(7));
        assertThat(javaViews).hasSize(2);

        List<LocalDateTime> allJobsViews = jobViewRepository.findViewedAtSince(acme.getId(), null, now.minusDays(7));
        assertThat(allJobsViews).hasSize(3);
    }

    @Test
    void viewsBeforeTheCutoffAreExcluded() {
        User acme = entityManager.persistAndFlush(employer("acme@example.com", "Acme"));
        Job job = entityManager.persistAndFlush(job(acme, "Java Developer"));

        LocalDateTime now = LocalDateTime.now();
        entityManager.persistAndFlush(view(job, now.minusDays(10)));
        entityManager.persistAndFlush(view(job, now.minusDays(1)));

        List<LocalDateTime> recentViews = jobViewRepository.findViewedAtSince(acme.getId(), null, now.minusDays(7));
        assertThat(recentViews).hasSize(1);
    }

    private User employer(String email, String companyName) {
        User user = new User();
        user.setFullName("Test Employer");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(Role.EMPLOYER);
        user.setEnabled(true);
        user.setCompanyName(companyName);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private Job job(User employer, String title) {
        Job job = new Job();
        job.setEmployer(employer);
        job.setTitle(title);
        job.setDescription("Backend role.");
        job.setRequirements("Some experience.");
        // No skills: this fixture never exercises them, and since Section 10.8 a job
        // with an empty skill list is perfectly valid to save.
        job.setCategory(JobCategory.SOFTWARE_DEVELOPMENT);
        job.setJobType(JobType.FULL_TIME);
        job.setWorkMode(WorkMode.ONSITE);
        job.setLocation("Pune");
        job.setSalaryMin(600_000);
        job.setSalaryMax(900_000);
        job.setMinExperienceYears(1);
        job.setOpenings(1);
        job.setApplicationDeadline(LocalDate.now().plusDays(30));
        job.setStatus(JobStatus.APPROVED);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private JobView view(Job job, LocalDateTime viewedAt) {
        JobView view = new JobView();
        view.setJob(job);
        view.setViewedAt(viewedAt);
        return view;
    }
}
