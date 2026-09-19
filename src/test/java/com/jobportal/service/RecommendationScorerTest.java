package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.Skill;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.ScoreResult;
import com.jobportal.util.SkillParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Pure unit tests for the scoring table of Section 7.8 (no Spring context needed -
// RecommendationScorer has no Spring dependencies, Section 12.2). Cases E1 to E8 as given
// in the plan; "today" is always fixed at 16 Sep 2026 to match FixedClockConfig and the
// worked examples in Section 7.8. E8 (tie-break ordering) is a service-level concern
// (RecommendationService sorts the scored results), so it is covered by
// RecommendationServiceTest instead of here.
class RecommendationScorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    // The default seeker used by every case unless the case overrides it (Section 7.8
    // "RecommendationScorerTest cases"): skills "Java, Spring Boot", location "Pune", 2
    // years' experience, prefers FULL_TIME, no past categories.
    private SeekerProfile defaultSeeker() {
        SeekerProfile profile = new SeekerProfile();
        profile.assignSkills(skills("Java, Spring Boot"));
        profile.setLocation("Pune");
        profile.setExperienceYears(2);
        profile.setPreferredJobType(JobType.FULL_TIME);
        return profile;
    }

    // Skill rows built in memory, never saved: RecommendationScorer has no Spring
    // dependencies (Section 12.2) and compares Skill.slug, which Skill.of fills in
    // without a database. Exactly what SkillService would produce for the same text,
    // because both go through SkillParser.
    private List<Skill> skills(String csv) {
        List<Skill> result = new ArrayList<>();
        for (String label : SkillParser.labels(csv)) {
            result.add(Skill.of(label));
        }
        return result;
    }

    private Job job(String title, String skills, String location, WorkMode workMode, JobType jobType,
            int minExperienceYears, JobCategory category, LocalDate approvedDate) {
        Job job = new Job();
        job.setTitle(title);
        job.assignSkills(skills(skills));
        job.setLocation(location);
        job.setWorkMode(workMode);
        job.setJobType(jobType);
        job.setMinExperienceYears(minExperienceYears);
        job.setCategory(category);
        job.setDescription("Description text.");
        job.setRequirements("Requirements text.");
        job.setApprovedAt(approvedDate == null ? null : approvedDate.atTime(9, 0));
        return job;
    }

    // E1: skills Java, Spring Boot, SQL; title "Java Developer"; Pune; Hybrid; Full-time;
    // 1 year; approved 3 days ago -> 10 + 10 + 5 + 5 + 4 + 2 = 36, qualified, Strong.
    @Test
    void e1AllSignalsMatch() {
        Job job = job("Java Developer", "Java, Spring Boot, SQL", "Pune", WorkMode.HYBRID, JobType.FULL_TIME, 1,
                JobCategory.SOFTWARE_DEVELOPMENT, TODAY.minusDays(3));

        ScoreResult result = RecommendationScorer.score(defaultSeeker(), Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(36);
        assertThat(result.qualified()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Java") && r.contains("Spring Boot"));
        assertThat(result.reasons()).contains("Full-time (your preference)", "In Pune",
                "Fits your 2 years' experience", "New this week");
    }

    // E2: skills Python; title "Python Developer"; Delhi; Onsite; Full-time; 0 years;
    // approved 2 days ago -> 5 (type) + 4 (experience) + 2 (freshness) = 11, not qualified
    // (no skill or category match).
    @Test
    void e2NoSkillMatchIsNotQualified() {
        Job job = job("Python Developer", "Python", "Delhi", WorkMode.ONSITE, JobType.FULL_TIME, 0,
                JobCategory.SOFTWARE_DEVELOPMENT, TODAY.minusDays(2));

        ScoreResult result = RecommendationScorer.score(defaultSeeker(), Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(11);
        assertThat(result.qualified()).isFalse();
    }

    // E3: skills React, JavaScript; title "Full Stack Java Engineer"; description mentions
    // "Spring Boot"; location "Remote (India)"; Remote; Contract; 2 years; approved 20 days
    // ago -> Java in title +6, Spring Boot in description +3, remote +3, experience +4 = 16.
    @Test
    void e3SkillsFoundInTitleAndDescription() {
        Job job = job("Full Stack Java Engineer", "React, JavaScript", "Remote (India)", WorkMode.REMOTE,
                JobType.CONTRACT, 2, JobCategory.SOFTWARE_DEVELOPMENT, TODAY.minusDays(20));
        job.setDescription("Hands-on work with Spring Boot services.");

        ScoreResult result = RecommendationScorer.score(defaultSeeker(), Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(16);
        assertThat(result.qualified()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Java") && r.contains("Spring Boot"));
        assertThat(result.reasons()).contains("Remote", "Fits your 2 years' experience");
        assertThat(result.reasons()).doesNotContain("Full-time (your preference)");
    }

    // E4: skills Java; Pune; Onsite; Full-time; 5 years required; approved 1 day ago ->
    // 10 (skill) + 5 (type) + 5 (location) - 10 (experience penalty) + 2 (freshness) = 12,
    // qualified, no experience reason (the penalty branch adds no reason text).
    @Test
    void e4LargeExperienceShortfallIsPenalised() {
        Job job = job("Java Engineer", "Java", "Pune", WorkMode.ONSITE, JobType.FULL_TIME, 5,
                JobCategory.SOFTWARE_DEVELOPMENT, TODAY.minusDays(1));

        ScoreResult result = RecommendationScorer.score(defaultSeeker(), Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(12);
        assertThat(result.qualified()).isTrue();
        assertThat(result.reasons()).noneMatch(r -> r.contains("experience"));
    }

    // E5: skills SQL; title "Data Engineer"; Mumbai; Onsite; Full-time; 4 years required;
    // category Data & Analytics; approved 30 days ago; seeker has past category Data &
    // Analytics -> type +5, experience short by 2 -> 0, category +3 = 8, qualified by
    // category alone (no skill match).
    @Test
    void e5QualifiesByCategoryAffinityAlone() {
        Job job = job("Data Engineer", "SQL", "Mumbai", WorkMode.ONSITE, JobType.FULL_TIME, 4,
                JobCategory.DATA_ANALYTICS, TODAY.minusDays(30));
        Set<JobCategory> pastCategories = EnumSet.of(JobCategory.DATA_ANALYTICS);

        ScoreResult result = RecommendationScorer.score(defaultSeeker(), pastCategories, job, TODAY);

        assertThat(result.score()).isEqualTo(8);
        assertThat(result.qualified()).isTrue();
        assertThat(result.reasons()).containsExactly("Full-time (your preference)", "Similar to jobs you applied for");
    }

    // E6: seeker skill "C", job title "Clerk", every other signal neutralised -> "c" is not
    // the word "clerk", so no skill match; total 0, not qualified.
    @Test
    void e6ShortSkillDoesNotMatchAsSubstring() {
        SeekerProfile seeker = new SeekerProfile();
        seeker.assignSkills(skills("C"));
        seeker.setExperienceYears(0); // no location, no preferred type

        Job job = job("Clerk", "", "Somewhere", WorkMode.ONSITE, JobType.FULL_TIME, 1, JobCategory.OTHER, null);

        ScoreResult result = RecommendationScorer.score(seeker, Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(0);
        assertThat(result.qualified()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    // E7: seeker skill "Node.js"; job description "Experience with Node JS required" ->
    // rule 3 (description/requirements) matches for +3, with every other signal
    // neutralised the same way as E6 so the whole score is exactly the rule-3 contribution.
    @Test
    void e7SkillMatchesDescriptionAsNodeJs() {
        SeekerProfile seeker = new SeekerProfile();
        seeker.assignSkills(skills("Node.js"));
        seeker.setExperienceYears(0);

        Job job = job("Backend Developer", "", "Somewhere", WorkMode.ONSITE, JobType.FULL_TIME, 1, JobCategory.OTHER,
                null);
        job.setDescription("Experience with Node JS required.");

        ScoreResult result = RecommendationScorer.score(seeker, Set.of(), job, TODAY);

        assertThat(result.score()).isEqualTo(3);
        assertThat(result.qualified()).isTrue();
        assertThat(result.reasons()).containsExactly("Matches your skills: Node.js");
    }
}
