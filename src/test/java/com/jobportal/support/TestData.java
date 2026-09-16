package com.jobportal.support;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.User;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.UserRepository;
import java.util.Locale;
import org.springframework.stereotype.Component;

// Looks up ids of the Section 13 seed data by a human-readable key (email or job title)
// instead of hard-coded numbers, so tests stay readable (Section 12.1: "data.jobId(...)",
// "data.userId(...)"). Backed by the real repositories, so it only works once DataSeeder
// has run - true for every IntegrationTestBase context, since the seeder runs at startup.
@Component
public class TestData {

    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;

    public TestData(UserRepository userRepository, JobRepository jobRepository,
            JobApplicationRepository jobApplicationRepository) {
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
    }

    public User user(String email) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalised)
                .orElseThrow(() -> new IllegalStateException("No seeded user with email " + email));
    }

    public Long userId(String email) {
        return user(email).getId();
    }

    // Job titles are unique in the seed data (Section 13.4), so the first match is enough.
    public Job job(String title) {
        return jobRepository.findAll().stream()
                .filter(j -> j.getTitle().equals(title))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No seeded job titled '" + title + "'"));
    }

    public Long jobId(String title) {
        return job(title).getId();
    }

    // The one application a seeker has for a job (Section 13.5: at most one per pair,
    // enforced by uk_application_job_seeker).
    public JobApplication application(String seekerEmail, String jobTitle) {
        Long seekerId = userId(seekerEmail);
        Long jobId = jobId(jobTitle);
        return jobApplicationRepository.findByJob_IdAndSeeker_Id(jobId, seekerId)
                .orElseThrow(() -> new IllegalStateException(
                        "No seeded application for " + seekerEmail + " -> " + jobTitle));
    }

    public Long applicationId(String seekerEmail, String jobTitle) {
        return application(seekerEmail, jobTitle).getId();
    }
}
