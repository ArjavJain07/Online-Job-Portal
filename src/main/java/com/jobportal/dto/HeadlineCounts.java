package com.jobportal.dto;

// Every scalar count /admin/statistics needs for one selected range, as returned by the
// single SQL statement in repository.AdminReportingJdbcRepository (Section 10.10).
//
// It is one record rather than twelve separate numbers because the twelve are fetched
// together, in one round-trip, and a caller that could take delivery of them one at a
// time would be free to go back to the database twelve times again. Three of them feed
// the KPI row, six feed the engagement table, and liveJobs/activeUsers7Days feed both
// (Section 7.6).
//
// Nothing here is formatted: these are raw counts, and the "N of M (P%)" / "A ÷ B = C"
// display strings are still AdminStatisticsService's job, the same as before. A record of
// longs is also what makes the equivalence test in AdminReportingJdbcRepositoryTest
// possible - every field can be compared directly with the JPA repository count it
// replaces, with no string parsing in between.
public record HeadlineCounts(
        // The KPI row (Section 6.2 A-D4), all four counted over the selected range.
        long newUsers,
        long jobsPosted,
        long applications,
        long hires,

        // Shown as a KPI and used again as the "Active users (7 days)" engagement
        // numerator: a fixed 7 day window whatever the selected range, so it is counted
        // from its own boundary, not from the range start (Section 7.6).
        long activeUsers7Days,

        // Approved, deadline not passed, employer still enabled - the same three
        // conditions as JobSpecifications.live(today) and Job.isLive(today).
        long liveJobs,

        // The engagement table's denominators and the two counts that go with them
        // (Section 7.6). "All" here means all time, deliberately: a participation rate
        // measures activity in the range against the whole population, not against the
        // part of the population that happens to have been created inside the range.
        long totalUsers,
        long enabledSeekers,
        long applyingSeekers,
        long allEmployers,
        long postingEmployers,
        long messagesSent) {
}
