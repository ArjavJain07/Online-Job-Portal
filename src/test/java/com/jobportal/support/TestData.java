package com.jobportal.support;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.Skill;
import com.jobportal.domain.User;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.SkillService;
import java.util.List;
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
    private final SkillService skillService;

    public TestData(UserRepository userRepository, JobRepository jobRepository,
            JobApplicationRepository jobApplicationRepository, SkillService skillService) {
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.skillService = skillService;
    }

    // Skill rows for a throwaway Job or SeekerProfile a test builds by hand
    // (data.skills("Java, SQL")). It has to go through SkillService rather than
    // Skill.of(...) because the rows are about to be persisted with the fixture: an
    // unsaved Skill on the other end of the @ManyToMany would fail the save outright.
    // Reuses whatever the seeder already created, so a test asking for "Java" gets the
    // same row every seeded job points at - which is the whole point of the relation.
    public List<Skill> skills(String csv) {
        return skillService.resolve(csv);
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
