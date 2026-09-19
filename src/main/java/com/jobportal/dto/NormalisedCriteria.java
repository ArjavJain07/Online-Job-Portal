package com.jobportal.dto;

import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import java.util.List;

// The parsed, validated form of a JobSearchCriteria (Section 7.9). Every raw string field
// becomes either a real typed value or null, meaning "no filter" - an unknown enum value,
// an out-of-range number or anything unparsable is simply dropped. minSalary is the one
// filter whose value is worth explaining to the visitor, so parsing it can add a message
// to warnings; every other field is silently ignored when it does not parse. Built once by
// JobSearchService.normalise() and read by JobSearchService.search().
public record NormalisedCriteria(
        String q,
        String location,
        JobCategory category,
        JobType jobType,
        WorkMode workMode,
        Integer minSalary,
        Integer maxExperience,
        Integer postedWithin,
        // The canonical slug of the selected skill facet, or null for "any skill"
        // (Section 10.8). A slug nobody has ever used is dropped like any other
        // unparsable filter, so a stale bookmark shows every job rather than none.
        String skill,
        String sort,
        int page,
        List<String> warnings) {
}
