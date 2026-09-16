package com.jobportal.dto;

import com.jobportal.domain.Job;

// One row of employer/job-history.html (Section 6.3 E-D4 columns: Title, Status,
// Submitted, Decision, Closed on, Applications, Hired, View). decisionText and
// closedOnText are pre-formatted strings (built in JobService with the "dd MMM yyyy"
// pattern used everywhere else, Section 7.1) so the template only has to th:text them,
// never format a date or branch on job.status itself.
public record EmployerJobHistoryRow(Job job, String decisionText, String closedOnText, long applications,
        long hired) {
}
