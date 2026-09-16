package com.jobportal.dto;

import com.jobportal.domain.Job;
import java.util.List;

// One job recommended to a seeker (Section 6.4 S-D5, 7.8): score stays internal (never
// shown to the seeker), label is "Strong match" / "Good match" / "Fair match", and
// reasons is the "Why recommended" line, already built in display order. In "Latest
// jobs" fallback mode (RecommendationResult.fallback() true) every entry instead carries
// label "Latest jobs", score 0 and no reasons, since there is nothing to explain.
public record RecommendedJob(Job job, int score, String label, List<String> reasons) {
}
