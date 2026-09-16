package com.jobportal.dto;

import java.util.List;

// The result of scoring one job against one seeker's profile (Section 7.8): the raw
// point total, whether the job qualifies to be recommended at all (at least one skill
// match or category affinity - a job is never recommended on freshness, location or
// experience fit alone), and the reasons to show, in the fixed order of the scoring
// table (skills, job type, location, experience, category, freshness). Returned by
// RecommendationScorer.score, which has no Spring dependencies, so RecommendationScorerTest
// can build a Job and a SeekerProfile by hand and assert on this record directly.
public record ScoreResult(int score, boolean qualified, List<String> reasons) {
}
