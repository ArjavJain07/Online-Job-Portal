package com.jobportal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobDisplayStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Confirms the seeded dataset matches Section 13 exactly - the Definition of Done for M1:
// "DataSeederTest confirms every count in 13.8" (Section 11.2).
class DataSeederTest extends IntegrationTestBase {

    @Autowired
    private DataSeeder dataSeeder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobApplicationRepository jobApplicationRepository;
    @Autowired
    private JobStatusChangeRepository jobStatusChangeRepository;
    @Autowired
    private ApplicationStatusChangeRepository applicationStatusChangeRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private Clock clock;

    @Test
    void createsSingleAdminIdempotently() {
        long usersBefore = userRepository.count();

        dataSeeder.run(); // running the seeder again must add nothing (Section 13.1)

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(countByRole(Role.ADMIN)).isEqualTo(1);
    }

    @Test
    void userCountsMatchSection13() {
        assertThat(userRepository.count()).isEqualTo(10);
        assertThat(countByRole(Role.ADMIN)).isEqualTo(1);
        assertThat(countByRole(Role.EMPLOYER)).isEqualTo(3);
        assertThat(countByRole(Role.JOB_SEEKER)).isEqualTo(6);

        long disabledEmployers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.EMPLOYER && !u.isEnabled())
                .count();
        assertThat(disabledEmployers).isEqualTo(1); // QuickHire, deactivated
    }

    @Test
    void jobCountsByStoredStatusMatchSection13() {
        Map<JobStatus, Long> byStatus = jobRepository.findAll().stream()
                .collect(Collectors.groupingBy(Job::getStatus, Collectors.counting()));

        assertThat(byStatus.getOrDefault(JobStatus.PENDING_APPROVAL, 0L)).isEqualTo(2L);
        assertThat(byStatus.getOrDefault(JobStatus.APPROVED, 0L)).isEqualTo(8L);
        assertThat(byStatus.getOrDefault(JobStatus.REJECTED, 0L)).isEqualTo(1L);
        assertThat(byStatus.getOrDefault(JobStatus.CLOSED, 0L)).isEqualTo(1L);
        assertThat(jobRepository.count()).isEqualTo(12L);
    }

    // Globex owns a rejected job (J9) and a closed one (J10) on top of its 3 active ones
    // (Section 13.2: "Second employer: ownership checks, rejected and closed jobs"), so its
    // Job.isActive() count (3) is deliberately smaller than its total job count (5).
    @Test
    void globexActiveJobCountExcludesRejectedAndClosed() {
        Long globexId = data.userId("talent@globex.local");
        long activeJobs = jobRepository.findAll().stream()
                .filter(j -> j.getEmployer().getId().equals(globexId))
                .filter(Job::isActive)
                .count();
        assertThat(activeJobs).isEqualTo(3L);
    }

    @Test
    void jobCountsByDisplayStatusMatchSection13() {
        LocalDate today = LocalDate.now(clock);
        Map<JobDisplayStatus, Long> byDisplayStatus = jobRepository.findAll().stream()
                .collect(Collectors.groupingBy(j -> j.displayStatus(today), Collectors.counting()));

        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.LIVE, 0L)).isEqualTo(6L);
        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.EXPIRED, 0L)).isEqualTo(1L);
        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.HIDDEN, 0L)).isEqualTo(1L);
        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.PENDING_APPROVAL, 0L)).isEqualTo(2L);
        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.REJECTED, 0L)).isEqualTo(1L);
        assertThat(byDisplayStatus.getOrDefault(JobDisplayStatus.CLOSED, 0L)).isEqualTo(1L);
    }

    @Test
    void applicationCountsByStatusMatchSection13() {
        Map<ApplicationStatus, Long> byStatus = jobApplicationRepository.findAll().stream()
                .collect(Collectors.groupingBy(JobApplication::getStatus, Collectors.counting()));

        assertThat(byStatus.getOrDefault(ApplicationStatus.APPLIED, 0L)).isEqualTo(3L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.UNDER_REVIEW, 0L)).isEqualTo(2L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.SHORTLISTED, 0L)).isEqualTo(2L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.INTERVIEW, 0L)).isEqualTo(1L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.HIRED, 0L)).isEqualTo(2L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.REJECTED, 0L)).isEqualTo(3L);
        assertThat(byStatus.getOrDefault(ApplicationStatus.WITHDRAWN, 0L)).isEqualTo(2L);
        assertThat(jobApplicationRepository.count()).isEqualTo(15L);
    }

    @Test
    void applicationsInLast30DaysMatchSection13() {
        LocalDateTime from = LocalDateTime.now(clock).minusDays(30);
        List<JobApplication> recent = jobApplicationRepository.findAll().stream()
                .filter(a -> !a.getAppliedAt().isBefore(from))
                .toList();

        assertThat(recent).hasSize(13);
        assertThat(recent.stream().map(a -> a.getAppliedAt().toLocalDate()).distinct().count()).isEqualTo(13L);
        assertThat(recent.stream().map(a -> a.getSeeker().getId()).distinct().count()).isEqualTo(4L);
    }

    @Test
    void acmeCountsMatchSection13() {
        Long acmeId = data.userId("hr@acme.local");
        LocalDateTime from = LocalDateTime.now(clock).minusDays(30);

        assertEmployerCounts(acmeId, from, 6, 4, 8, 8);
        assertThat(messageRepository.countByRecipient_IdAndReadAtIsNull(acmeId)).isEqualTo(1L);
    }

    @Test
    void globexCountsMatchSection13() {
        Long globexId = data.userId("talent@globex.local");
        LocalDateTime from = LocalDateTime.now(clock).minusDays(30);

        assertEmployerCounts(globexId, from, 5, 2, 7, 5);
    }

    @Test
    void historyRowCountsMatchSection13() {
        assertThat(applicationStatusChangeRepository.count()).isEqualTo(41L); // 15 Applied + 26 later changes
        assertThat(jobStatusChangeRepository.count()).isEqualTo(23L); // 12 Posted + 9 approved + 1 rejected + 1 closed
    }

    @Test
    void messageCountsMatchSection13() {
        assertThat(messageRepository.count()).isEqualTo(8L);
        long unread = messageRepository.findAll().stream().filter(m -> m.getReadAt() == null).count();
        assertThat(unread).isEqualTo(3L);

        assertThat(messageRepository.countByRecipient_IdAndReadAtIsNull(data.userId("priya@demo.local")))
                .isEqualTo(1L);
        assertThat(messageRepository.countByRecipient_IdAndReadAtIsNull(data.userId("rohan@demo.local")))
                .isEqualTo(1L);
    }

    @Test
    void activeUsersInLast7DaysMatchesSection13() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now(clock).minusDays(7);
        assertThat(userRepository.countByLastLoginAtGreaterThanEqual(sevenDaysAgo)).isEqualTo(6L);
    }

    // Section 13.8's job count is the employer's total jobs, not just the active ones: for
    // Acme all 6 happen to be active or pending, so the two numbers coincide, but Globex
    // also owns a rejected (J9) and a closed (J10) job, so its "5 jobs" is deliberately the
    // total (Section 13.2: Globex exists partly to exercise rejected and closed jobs).
    private void assertEmployerCounts(Long employerId, LocalDateTime from, int expectedTotalJobs,
            int expectedLiveJobs, int expectedApplications, int expectedApplicationsIn30Days) {
        LocalDate today = LocalDate.now(clock);
        List<Job> jobs = jobRepository.findAll().stream()
                .filter(j -> j.getEmployer().getId().equals(employerId))
                .toList();
        long liveJobs = jobs.stream().filter(j -> j.displayStatus(today) == JobDisplayStatus.LIVE).count();

        List<JobApplication> applications = jobApplicationRepository.findAll().stream()
                .filter(a -> a.getJob().getEmployer().getId().equals(employerId))
                .toList();
        long recentApplications = applications.stream().filter(a -> !a.getAppliedAt().isBefore(from)).count();

        assertThat(jobs).hasSize(expectedTotalJobs);
        assertThat(liveJobs).isEqualTo(expectedLiveJobs);
        assertThat(applications).hasSize(expectedApplications);
        assertThat(recentApplications).isEqualTo(expectedApplicationsIn30Days);
    }

    private long countByRole(Role role) {
        return userRepository.findAll().stream().filter(u -> u.getRole() == role).count();
    }
}
