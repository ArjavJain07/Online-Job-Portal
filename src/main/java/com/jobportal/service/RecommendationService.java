package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.dto.RecommendedJob;
import com.jobportal.dto.ScoreResult;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.SeekerProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Personalised job recommendations for a seeker (Section 6.4 S-D5, 7.8). Applies
// RecommendationScorer to a pool of candidate jobs the seeker has not already applied
// to, keeps the qualifying ones, sorts and limits them - or falls back to the newest
// Live jobs when the seeker has given the algorithm nothing to work with.
@Service
public class RecommendationService {

    // Step 3 (7.8): a generous pool so a seeker with broad skills still gets a fair
    // ranking, capped well short of "every Live job" for performance.
    static final int CANDIDATE_POOL_SIZE = 200;

    // Step 5 (7.8): the minimum score to be shown at all, on top of the qualification
    // rule (at least one skill match or category affinity).
    static final int QUALIFYING_THRESHOLD = 5;

    // Step 6 (7.8) label thresholds.
    static final int STRONG_MATCH_THRESHOLD = 25;
    static final int GOOD_MATCH_THRESHOLD = 12;

    private final JobRepository jobRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final Clock clock;

    public RecommendationService(JobRepository jobRepository, SeekerProfileRepository seekerProfileRepository,
            JobApplicationRepository jobApplicationRepository, Clock clock) {
        this.jobRepository = jobRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.clock = clock;
    }

    // Section 7.8 steps 1-6. Read-only: recommending never changes any data, so this
    // never needs ActivityLogService (11.3 contract item 5 only requires logging for
    // state changes).
    @Transactional(readOnly = true)
    public RecommendationResult recommend(Long seekerId, int limit) {
        LocalDate today = LocalDate.now(clock);
        SeekerProfile profile = seekerProfileRepository.findByUser_Id(seekerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seeker profile for user " + seekerId + " does not exist"));

        // Step 1: every application the seeker has ever made, any status - both to build
        // the past-category set and to test the fallback condition below. Reuses the
        // same finder the seeker's own "active applications" list uses (S-D2), just with
        // every status instead of only the active ones.
        List<JobApplication> pastApplications = jobApplicationRepository.findBySeeker_IdAndStatusIn(seekerId,
                EnumSet.allOf(ApplicationStatus.class), Sort.unsorted());

        boolean hasSkills = profile.getSkills() != null && !profile.getSkills().isBlank();
        boolean hasApplications = !pastApplications.isEmpty();

        // Step 2: the fallback. A seeker who has given the scorer nothing to match on
        // (no skills typed in, and never applied anywhere) would otherwise see an empty
        // "no matches" page on their very first visit.
        if (!hasSkills && !hasApplications) {
            return new RecommendationResult(latestJobsFallback(today, limit), true);
        }

        Set<JobCategory> pastCategories = new LinkedHashSet<>();
        for (JobApplication application : pastApplications) {
            pastCategories.add(application.getJob().getCategory());
        }

        // Step 3: candidates are Live jobs this seeker has not already applied to,
        // newest-approved first, capped to the pool size.
        Specification<Job> candidateSpec = JobSpecifications.live(today).and(JobSpecifications.notAppliedBy(seekerId));
        Pageable candidatePage = PageRequest.of(0, CANDIDATE_POOL_SIZE, Sort.by(Sort.Order.desc("approvedAt")));
        List<Job> candidates = jobRepository.findAll(candidateSpec, candidatePage).getContent();

        // Steps 4-5: score every candidate, keep the qualifying ones scoring at least
        // the threshold.
        List<RecommendedJob> matches = new ArrayList<>();
        for (Job job : candidates) {
            ScoreResult result = RecommendationScorer.score(profile, pastCategories, job, today);
            if (result.qualified() && result.score() >= QUALIFYING_THRESHOLD) {
                matches.add(new RecommendedJob(job, result.score(), labelFor(result.score()), result.reasons()));
            }
        }

        // Step 5: sort by score descending, then by how recently the job was approved
        // (E8: two jobs tied on score show the more recently approved one first).
        matches.sort(Comparator.comparingInt(RecommendedJob::score).reversed()
                .thenComparing(recommended -> recommended.job().getApprovedAt(), Comparator.reverseOrder()));

        List<RecommendedJob> limited = matches.size() > limit ? matches.subList(0, limit) : matches;
        return new RecommendationResult(List.copyOf(limited), false);
    }

    // The "Latest jobs" fallback (7.8 step 2): the newest Live jobs, wrapped as
    // RecommendedJob so the template can use the same job-card rendering either way.
    private List<RecommendedJob> latestJobsFallback(LocalDate today, int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id")));
        Page<Job> latest = jobRepository.findAll(JobSpecifications.live(today), pageable);
        List<RecommendedJob> fallback = new ArrayList<>();
        for (Job job : latest) {
            fallback.add(new RecommendedJob(job, 0, "Latest jobs", List.of()));
        }
        return fallback;
    }

    private String labelFor(int score) {
        if (score >= STRONG_MATCH_THRESHOLD) {
            return "Strong match";
        }
        if (score >= GOOD_MATCH_THRESHOLD) {
            return "Good match";
        }
        return "Fair match";
    }
}
