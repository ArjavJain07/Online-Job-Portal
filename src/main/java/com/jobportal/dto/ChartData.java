package com.jobportal.dto;

import java.util.List;

// One Chart.js-ready series passed to a template (Section 7.6): a label for the series,
// the bucket labels shown on the axis, and one value per bucket. Only strings and
// numbers, so Thymeleaf's JavaScript inlining can serialise it safely.
public record ChartData(String label, List<String> labels, List<Long> values) {
}
