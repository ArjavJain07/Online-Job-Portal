package com.jobportal.dto;

// One option in the employer "Change status" dropdown (Section 6.3 E-F2): the enum name
// (submitted back as the form value) and its display label. Built in
// EmployerApplicationController rather than read from ApplicationStatus directly in the
// template - see the comment on employer/application-detail.html's status switch for why.
public record ApplicationStatusOption(String name, String label) {
}
