package com.jobportal.dto;

// One row of the employer statistics "per-job" table (Section 6.3 E-D5): all time,
// because view counts are not dated. status is the job's display label (Live, Expired,
// Pending approval, ...), already computed by JobStatsRow's caller with today's date, so
// the template never has to call a Job method with a computed argument itself (7.1
// Thymeleaf 3.1 restricted-expression trap). applyRate is pre-formatted ("10.0%", or the
// en dash placeholder when views is 0, Section 7.6) by EmployerStatisticsService#applyRate.
public record JobStatsRow(Long jobId, String title, String status, long views, long applications, String applyRate,
        long shortlistedOrFurther, long hired) {
}
