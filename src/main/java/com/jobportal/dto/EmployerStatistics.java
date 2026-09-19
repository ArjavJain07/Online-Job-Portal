package com.jobportal.dto;

import java.util.List;

// Everything /employer/statistics.html needs for one selected range and optional job
// filter (Section 6.3 E-D5, 7.6): days is the normalised 7/30/90 and jobId echoes back
// the narrowed job (null means "all of this employer's jobs"), so the template can
// re-select both on the range buttons and the job dropdown. messagesOverTime holds
// exactly two series, "Employer messages" then "Candidate replies" (same bucket labels),
// meant for fragments/chart-card :: multi. jobStats and engagementMetrics are always
// scoped to the current employer only. Built by EmployerStatisticsService.getStatistics.
//
// viewsOverTime and viewToApplicationFunnel are the dated view analytics feature (built
// from the JobView table, not Job.viewCount - see JobView's class comment for why the two
// are separate). Both respect days and jobId exactly like applicationsOverTime, which is
// the point of adding a dated table at all: the existing per-job Views column stays
// all-time (view counts still are not dated there - Section 7.6), but these two are scoped
// to the selected window, same as every other chart on this page.
public record EmployerStatistics(
        int days,
        Long jobId,
        List<KpiValue> kpis,
        ChartData applicationsOverTime,
        ChartData hiringPipeline,
        ChartData applicationsPerJob,
        List<ChartData> messagesOverTime,
        ChartData viewsOverTime,
        ChartData viewToApplicationFunnel,
        List<JobStatsRow> jobStats,
        List<EngagementMetric> engagementMetrics) {
}
