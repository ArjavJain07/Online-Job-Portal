package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.WorkMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

// Repository-slice test for JobApplicationRepository (Section 12.2): the DB-level
// uk_application_job_seeker unique constraint (S-F2, "one application per seeker per job").
@DataJpaTest
class JobApplicationRepositoryTest {

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void uniqueJobSeekerConstraint() {
        User employer = entityManager.persistAndFlush(newEmployer());
        User seeker = entityManager.persistAndFlush(newSeeker());
        Job job = entityManager.persistAndFlush(newJob(employer));

        jobApplicationRepository.saveAndFlush(newApplication(job, seeker));

        JobApplication second = newApplication(job, seeker);
        assertThatThrownBy(() -> jobApplicationRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameJobDifferentSeekersIsAllowed() {
        User employer = entityManager.persistAndFlush(newEmployer());
        User seekerOne = entityManager.persistAndFlush(newSeeker());
        User seekerTwo = entityManager.persistAndFlush(newSeekerWithEmail("seeker2@example.com"));
        Job job = entityManager.persistAndFlush(newJob(employer));

        jobApplicationRepository.saveAndFlush(newApplication(job, seekerOne));
        // Must not throw: the unique constraint is per (job, seeker), not per job alone.
        jobApplicationRepository.saveAndFlush(newApplication(job, seekerTwo));
    }

    private User newEmployer() {
        User user = new User();
        user.setFullName("Anita Rao");
        user.setEmail("employer@example.com");
        user.setPasswordHash("hash");
        user.setRole(Role.EMPLOYER);
        user.setEnabled(true);
        user.setCompanyName("Acme Technologies");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private User newSeeker() {
        return newSeekerWithEmail("seeker@example.com");
    }

    private User newSeekerWithEmail(String email) {
        User user = new User();
        user.setFullName("Test Seeker");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(Role.JOB_SEEKER);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    private Job newJob(User employer) {
        Job job = new Job();
        job.setEmployer(employer);
        job.setTitle("Java Developer");
        job.setDescription("Backend role.");
        job.setRequirements("Java experience.");
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

    private JobApplication newApplication(Job job, User seeker) {
        JobApplication application = new JobApplication();
        application.setJob(job);
        application.setSeeker(seeker);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setResumeStoredName("11111111-1111-1111-1111-111111111111.pdf");
        application.setResumeOriginalName("resume.pdf");
        application.setResumeContentType("application/pdf");
        application.setResumeSizeBytes(1024L);
        application.setAppliedAt(LocalDateTime.now());
        return application;
    }
}
