package com.jobportal.dto;

import java.util.List;

// The result of RecommendationService.recommend(seekerId, limit) (Section 6.4 S-D5, 7.8):
// jobs is the list to render (scored recommendations, or the "Latest jobs" fallback -
// never both), and fallback tells the controller which heading and prompt to show
// ("Latest jobs" plus "Add your skills, location and preferred job type to get
// personalised recommendations." vs the normal recommendations heading). Kept as its own
// small record, the same pattern as JobSearchResult, because the fallback flag is a
// business decision (Section 7.8 step 2) that belongs in the service, not re-derived by
// the controller from the shape of the list.
public record RecommendationResult(List<RecommendedJob> jobs, boolean fallback) {
}
