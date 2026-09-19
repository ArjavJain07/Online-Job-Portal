package com.jobportal.dto;

// One entry of the skill facet shown beside the job search results (Section 10.8):
// "Java (24)". slug is what the "skill" query parameter carries - a stable canonical key
// rather than a display spelling, so a bookmarked facet link keeps working even if the
// label the skill is rendered under changes. jobCount is how many jobs the current search
// would return if this facet were the selected one, and selected says whether it already
// is, so the template can render the "remove this filter" state without comparing strings
// itself (Section 7.1: templates do no logic).
public record SkillFacet(String slug, String label, long jobCount, boolean selected) {
}
