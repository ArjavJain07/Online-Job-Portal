package com.jobportal.dto;

// One row of a "User engagement" / "Candidate engagement" table (Section 7.6): the
// metric's name, its formula written out in plain English (so the table doubles as
// documentation, exactly as Section 7.6 itself lists them), and the computed value,
// already formatted ("4 of 6 (67%)", "24.0 hours", "8").
public record EngagementMetric(String name, String formula, String value) {
}
