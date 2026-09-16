package com.jobportal.dto;

// One number card on a statistics page (Section 7.6): a label ("Applications"), the
// already-formatted value to display ("13") and an optional short hint shown under it
// (null when there is nothing extra to say). Kept as plain strings, not a number, because
// some values are already formatted (percentages, "N of M") before they ever reach a
// template - see AdminStatisticsService and EmployerStatisticsService.
public record KpiValue(String label, String value, String hint) {
}
