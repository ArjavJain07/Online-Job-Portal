package com.jobportal.dto;

import com.jobportal.domain.JobApplication;

// One row of seeker/application-history.html (Section 6.4 S-D4 columns: Job, Company,
// Applied on, Result, Decided on, Duration, View). resultLabel, decidedOnText and
// durationText are pre-formatted (Section 7.1: templates never format a date or branch on
// status themselves) - resultLabel and decidedOnText both read "In progress" for an
// active application shown only because view=all (Section 6.4 S-D4 "Toggle 'All
// applications'"), and durationText is "-" for the same rows, since neither a result nor a
// duration exists yet. Built by JobApplicationService.historyForSeeker(...).
public record SeekerApplicationHistoryRow(JobApplication application, String resultLabel, String decidedOnText,
        String durationText) {
}
