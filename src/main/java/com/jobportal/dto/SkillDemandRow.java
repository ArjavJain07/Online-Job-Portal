package com.jobportal.dto;

// One row of the "Most in-demand skills" report (Section 10.10): a skill's display label
// and the number of Live jobs that ask for it.
//
// label, not slug: the slug is the skill's identity and is never shown (domain.Skill), and
// this record only ever travels from the SQL straight into a ChartData for the page. If a
// future caller needs to link a row to /jobs?skill=<slug>, the slug belongs here as a
// second field - adding it now would be a column nothing reads, which Section 10.8's own
// rule about kept columns argues against.
//
// liveJobs is a count of DISTINCT jobs, not of job_skills rows - see the query for why
// that distinction is not merely defensive.
public record SkillDemandRow(String label, long liveJobs) {
}
