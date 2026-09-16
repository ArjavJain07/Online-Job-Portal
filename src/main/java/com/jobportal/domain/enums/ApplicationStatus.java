package com.jobportal.domain.enums;

import java.util.EnumSet;
import java.util.Set;

// The stored lifecycle of a job application (Section 5.6). Two labels are kept because
// employers and admins see "Rejected" while seekers see the softer "Not selected".
public enum ApplicationStatus {

    APPLIED("Applied", "Applied"),
    UNDER_REVIEW("Under review", "Under review"),
    SHORTLISTED("Shortlisted", "Shortlisted"),
    INTERVIEW("Interview", "Interview"),
    HIRED("Hired", "Hired"),
    REJECTED("Rejected", "Not selected"),
    WITHDRAWN("Withdrawn", "Withdrawn");

    private final String label;        // employers and admins
    private final String seekerLabel;  // job seekers

    ApplicationStatus(String label, String seekerLabel) {
        this.label = label;
        this.seekerLabel = seekerLabel;
    }

    // Statuses this one may move to. Empty for the three final statuses.
    public Set<ApplicationStatus> allowedNext() {
        return switch (this) {
            case APPLIED      -> EnumSet.of(UNDER_REVIEW, SHORTLISTED, REJECTED, WITHDRAWN);
            case UNDER_REVIEW -> EnumSet.of(SHORTLISTED, REJECTED, WITHDRAWN);
            case SHORTLISTED  -> EnumSet.of(INTERVIEW, HIRED, REJECTED, WITHDRAWN);
            case INTERVIEW    -> EnumSet.of(HIRED, REJECTED, WITHDRAWN);
            case HIRED, REJECTED, WITHDRAWN -> EnumSet.noneOf(ApplicationStatus.class);
        };
    }

    public boolean canTransitionTo(ApplicationStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isActive() {
        return !allowedNext().isEmpty();
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }

    // Options for the employer's dropdown: everything allowed except WITHDRAWN, which
    // only the seeker can choose.
    public Set<ApplicationStatus> employerOptions() {
        Set<ApplicationStatus> options = EnumSet.noneOf(ApplicationStatus.class);
        options.addAll(allowedNext());
        options.remove(WITHDRAWN);
        return options;
    }

    public String getLabel() {
        return label;
    }

    public String getSeekerLabel() {
        return seekerLabel;
    }
}
