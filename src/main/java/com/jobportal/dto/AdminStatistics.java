package com.jobportal.dto;

import java.util.List;

// Everything /admin/statistics.html needs for one selected range (Section 6.2 A-D4,
// 7.6): days is the normalised 7/30/90 so the template can highlight the active range
// button. Every ChartData and KpiValue here holds only strings and numbers, so
// Thymeleaf's JavaScript inlining can serialise the chart fields straight into the page
// (Section 7.6). Built by AdminStatisticsService.getStatistics(String).
public record AdminStatistics(
        int days,
        List<KpiValue> kpis,
        ChartData applicationsOverTime,
        ChartData jobsPostedOverTime,
        ChartData registrationsOverTime,
        ChartData jobsByStatus,
        ChartData applicationsByCategory,
        ChartData applicationOutcomes,
        ChartData loginsOverTime,

        // Most in-demand skills: the top TOP_SKILLS_LIMIT skills by how many Live jobs ask
        // for them, already in rank order (Section 10.10). A ChartData like the other
        // seven and not a List of its own row type, precisely so it goes through the same
        // fragments/chart-card as everything else - the labels are skill names instead of
        // dates or enum labels, which the fragment neither knows nor needs to.
        //
        // Unlike every other field here it is NOT scoped to the selected range: "in
        // demand" is a fact about the jobs that are open right now, so the 7/30/90 pills
        // do not change it. The card's own title on the page says so, because a chart
        // sitting under a range control that ignores the range has to.
        ChartData skillDemand,

        List<TopJobRow> topJobs,
        List<EngagementMetric> engagementMetrics) {
}
