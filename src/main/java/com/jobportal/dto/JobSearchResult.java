package com.jobportal.dto;

import com.jobportal.domain.Job;
import java.util.List;
import org.springframework.data.domain.Page;

// What JobSearchService.search() returns (Section 7.9): the page of matching Live jobs,
// the normalised criteria that produced it, and any warnings to show above the results
// (for example an unparsable minSalary that was ignored). warnings is the same list as
// criteria.warnings() - kept as its own component because that is how the plan's
// pseudocode builds it, and because a controller only needs this one field to fill the
// "warning" flash attribute without reaching into criteria.
public record JobSearchResult(Page<Job> jobs, NormalisedCriteria criteria, List<String> warnings) {
}
