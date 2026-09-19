package com.jobportal.dto;

import com.jobportal.domain.Job;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;


// What JobSearchService.search() returns (Section 7.9): the page of matching Live jobs,
// the normalised criteria that produced it, and any warnings to show above the results
// (for example an unparsable minSalary that was ignored). warnings is the same list as
// criteria.warnings() - kept as its own component because that is how the plan's
// pseudocode builds it, and because a controller only needs this one field to fill the
// "warning" flash attribute without reaching into criteria.
// skillFacets (Section 10.8) is the "Java (24)" list beside the results: every skill the
// current search's jobs list, with how many of them list it, most-used first. It is
// computed against the search WITHOUT its own skill filter applied, which is what makes
// a facet list usable - with the filter applied, picking "Java" would leave "Java (24)"
// as the only row and there would be no way to switch to another skill without clearing
// the filter first.
// matchReasons (Section 10.9) is the "why is this here" line under each result card, keyed
// by job id: "Matched in title and skills". Empty whenever the search has no keyword, which
// is what tells the templates to render nothing - a list that is not the answer to a
// question does not need to justify itself.
//
// It is a plain Map keyed by id rather than a field on some new row type because the two
// results pages iterate different things (public/jobs.html walks the Page of jobs directly,
// seeker/jobs.html walks SeekerJobRow), and fragments/job-card is shared by both. That is
// the same shape the two-pane layout's "selectedJobId" already uses and for the same
// reason: one plain model attribute the fragment reads for itself, instead of a third
// parameter on a fragment signature every caller would have to change.
public record JobSearchResult(Page<Job> jobs, NormalisedCriteria criteria, List<String> warnings,
        List<SkillFacet> skillFacets, Map<Long, String> matchReasons) {
}
