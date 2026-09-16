package com.jobportal.domain.enums;

// The status shown to users, computed by Job.displayStatus(today) (Section 5.5). Never
// stored: APPROVED alone splits into Live, Expired or Hidden depending on the deadline
// and the employer's account.
public enum JobDisplayStatus {

    LIVE("Live"),
    EXPIRED("Expired"),
    HIDDEN("Hidden (employer deactivated)"),
    PENDING_APPROVAL("Pending approval"),
    REJECTED("Rejected"),
    CLOSED("Closed");

    private final String label;

    JobDisplayStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
