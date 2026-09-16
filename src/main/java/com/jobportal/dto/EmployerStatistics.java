package com.jobportal.dto;

import java.util.List;

// Everything /employer/statistics.html needs for one selected range and optional job
// filter (Section 6.3 E-D5, 7.6): days is the normalised 7/30/90 and jobId echoes back
// the narrowed job (null means "all of this employer's jobs"), so the template can
// re-select both on the range buttons and the job dropdown. messagesOverTime holds
// exactly two series, "Employer messages" then "Candidate replies" (same bucket labels),
// meant for fragments/chart-card :: multi. jobStats and engagementMetrics are always
// scoped to the current employer only. Built by EmployerStatisticsService.getStatistics.
public record EmployerStatistics(
        int days,
        Long jobId,
        List<KpiValue> kpis,
        ChartData applicationsOverTime,
        ChartData hiringPipeline,
        ChartData applicationsPerJob,
        List<ChartData> messagesOverTime,
        List<JobStatsRow> jobStats,
        List<EngagementMetric> engagementMetrics) {
}
