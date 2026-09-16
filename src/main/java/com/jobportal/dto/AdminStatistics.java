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
        List<TopJobRow> topJobs,
        List<EngagementMetric> engagementMetrics) {
}
