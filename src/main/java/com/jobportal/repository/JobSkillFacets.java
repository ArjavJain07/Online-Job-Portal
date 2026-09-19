package com.jobportal.repository;

import com.jobportal.domain.Job;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

// Custom fragment of JobRepository (Section 10.8): "for the jobs this Specification
// selects, how many list each skill".
//
// It is a fragment rather than an @Query method because the answer has to respect the
// very same Specification the search itself was built from. Writing it as JPQL with a
// dozen optional ":x is null or ..." parameters would be a second copy of
// JobSearchService.search's filter chain, and two copies of a filter chain are two things
// that eventually disagree - the facet counts would then quietly describe a different set
// of jobs from the results next to them. Building it with the Criteria API lets the one
// Specification drive both.
public interface JobSkillFacets {

    // Ordered most-used first, ties broken by label so the list is stable between
    // requests. Skills no matching job lists are absent rather than present with a zero -
    // a facet nobody can click is noise.
    List<SkillCount> countSkills(Specification<Job> spec, int limit);

    // Deliberately not dto.SkillFacet: that record carries "is this the selected facet",
    // which is a property of the request, not of the database.
    record SkillCount(String slug, String label, long jobCount) {
    }
}
