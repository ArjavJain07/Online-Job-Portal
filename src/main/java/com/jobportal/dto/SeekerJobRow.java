package com.jobportal.dto;

import com.jobportal.domain.Job;

// One row of seeker/jobs.html (Section 6.4 S-F1 output, S-D1): a job plus whether this
// seeker has already applied to it (any status, including withdrawn - the duplicate rule
// counts a withdrawal as final, I-15). Paired here instead of looked up again inside the
// template because Thymeleaf 3.1's restricted-expression mode rejects a bean call with a
// computed argument (appliedJobIds.contains(job.id)) inside a th:replace fragment call -
// the same trap PageLinks.build(Page) and fragments/job-card.html's own comment describe.
// Built once by JobApplicationService.withAppliedFlags(...).
public record SeekerJobRow(Job job, boolean applied) {
}
