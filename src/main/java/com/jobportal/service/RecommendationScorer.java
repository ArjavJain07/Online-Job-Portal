package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.Skill;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.ScoreResult;
import com.jobportal.util.TextMatcher;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// The pure, rule-based recommendation score (Section 7.8): no Spring dependencies, so
// RecommendationScorerTest can build a Job and a SeekerProfile by hand and call
// score(...) directly, with no database or web context. RecommendationService is the
// only caller in the running application; it supplies the candidate jobs, the seeker's
// past categories and "today".
public final class RecommendationScorer {

    static final int SKILL_IN_JOB_SKILLS_POINTS = 10;
    static final int SKILL_IN_TITLE_POINTS = 6;
    static final int SKILL_IN_DESCRIPTION_POINTS = 3;
    static final int JOB_TYPE_POINTS = 5;
    static final int LOCATION_MATCH_POINTS = 5;
    static final int REMOTE_POINTS = 3;
    static final int EXPERIENCE_FIT_POINTS = 4;
    static final int EXPERIENCE_SHORTFALL_PENALTY = -10;
    // Short by 1 or this many years scores 0 (no reason); short by more than this scores
    // the penalty above.
    static final int EXPERIENCE_MINOR_SHORTFALL_YEARS = 2;
    static final int CATEGORY_AFFINITY_POINTS = 3;
    static final int FRESHNESS_POINTS = 2;
    static final int FRESHNESS_WINDOW_DAYS = 7;

    private RecommendationScorer() {
    }

    // Scores one Live job against one seeker (Section 7.8 scoring table). Each of the
    // seeker's skills scores once, under the first rule that matches (job's own skill
    // list, then title, then description/requirements); every other signal is all-or-
    // nothing. A job qualifies to be recommended only through a skill match or category
    // affinity (signals 1-3 and 7) - freshness, location, job type and experience fit
    // can add points to an already-qualifying job, but never qualify one on their own.
    //
    // RULE 1 NOW COMPARES SKILL IDENTITY, NOT SPELLINGS (Section 10.8)
    // It used to lower-case both skill lists and compare the strings, so a seeker's
    // "Node.js" did not match a job's "Node JS" under rule 1 - even though Section 7.8's
    // own case E7 says those are the same skill, and rule 3 duly matched them in the job's
    // description text. The two sides now hold the same Skill rows, so rule 1 compares
    // Skill.slug and that inconsistency is gone: anything rules 2-3 would recognise as the
    // same phrase, rule 1 recognises as the same skill.
    //
    // This does not move any existing score. Rule 1 was already exact equality (a Set
    // lookup, not a substring test), so it only ever gains matches that previously fell
    // through to rule 2 or rule 3, and only for spelling variants of a skill the job
    // genuinely lists. For every skill pair in the seed data and in cases E1-E7 the two
    // rules agree, which is why those expectations are unchanged.
    public static ScoreResult score(SeekerProfile profile, Set<JobCategory> pastCategories, Job job, LocalDate today) {
        int total = 0;
        boolean qualified = false;
        List<String> reasons = new ArrayList<>();

        List<String> matchedSkills = new ArrayList<>();
        Set<String> jobSkillSlugs = slugs(job.getSkills());
        String descriptionAndRequirements = safe(job.getDescription()) + " " + safe(job.getRequirements());
        for (Skill skill : profile.getSkills()) {
            if (jobSkillSlugs.contains(skill.getSlug())) {
                total += SKILL_IN_JOB_SKILLS_POINTS;
                matchedSkills.add(skill.getLabel());
            } else if (TextMatcher.containsPhrase(job.getTitle(), skill.getLabel())) {
                total += SKILL_IN_TITLE_POINTS;
                matchedSkills.add(skill.getLabel());
            } else if (TextMatcher.containsPhrase(descriptionAndRequirements, skill.getLabel())) {
                total += SKILL_IN_DESCRIPTION_POINTS;
                matchedSkills.add(skill.getLabel());
            }
        }
        if (!matchedSkills.isEmpty()) {
            qualified = true;
            reasons.add("Matches your skills: " + String.join(", ", matchedSkills));
        }

        if (profile.getPreferredJobType() != null && profile.getPreferredJobType() == job.getJobType()) {
            total += JOB_TYPE_POINTS;
            reasons.add(job.getJobType().getLabel() + " (your preference)");
        }

        String profileLocation = profile.getLocation();
        if (profileLocation != null && !profileLocation.isBlank() && job.getLocation() != null
                && job.getLocation().toLowerCase(Locale.ROOT).contains(profileLocation.trim().toLowerCase(Locale.ROOT))) {
            total += LOCATION_MATCH_POINTS;
            reasons.add("In " + profileLocation.trim());
        } else if (job.getWorkMode() == WorkMode.REMOTE) {
            total += REMOTE_POINTS;
            reasons.add("Remote");
        }

        int experienceYears = profile.getExperienceYears();
        int shortfallYears = job.getMinExperienceYears() - experienceYears;
        if (shortfallYears <= 0) {
            total += EXPERIENCE_FIT_POINTS;
            reasons.add("Fits your " + experienceYears + (experienceYears == 1 ? " year's" : " years'") + " experience");
        } else if (shortfallYears > EXPERIENCE_MINOR_SHORTFALL_YEARS) {
            total += EXPERIENCE_SHORTFALL_PENALTY;
        }
        // A shortfall of 1 or 2 years falls through both branches above: 0 points, no
        // reason.

        if (pastCategories != null && pastCategories.contains(job.getCategory())) {
            total += CATEGORY_AFFINITY_POINTS;
            qualified = true;
            reasons.add("Similar to jobs you applied for");
        }

        if (job.getApprovedAt() != null) {
            long daysSinceApproval = ChronoUnit.DAYS.between(job.getApprovedAt().toLocalDate(), today);
            if (daysSinceApproval >= 0 && daysSinceApproval < FRESHNESS_WINDOW_DAYS) {
                total += FRESHNESS_POINTS;
                reasons.add("New this week");
            }
        }

        return new ScoreResult(total, qualified, reasons);
    }

    private static Set<String> slugs(List<Skill> skills) {
        Set<String> result = new LinkedHashSet<>();
        for (Skill skill : skills) {
            result.add(skill.getSlug());
        }
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
