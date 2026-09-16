package com.jobportal.dto;

// One row of the admin statistics "Top 5 most-applied jobs" table (Section 6.2 A-D4):
// applications is the count submitted in the selected range (TopJobCount, Section 7.6);
// hired is that job's all-time hire count, since a hire the job produced does not stop
// counting just because it happened before the selected range started.
public record TopJobRow(Long jobId, String title, String company, long applications, long hired) {
}
