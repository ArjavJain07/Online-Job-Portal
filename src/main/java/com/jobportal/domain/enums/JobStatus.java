package com.jobportal.domain.enums;

// The stored lifecycle of a job posting (Section 5.5). Live, Expired and Hidden are
// computed labels, not values of this enum - see JobDisplayStatus and Job.displayStatus.
// Transition rules (who may move a job from one status to another) live in the service
// layer (JobService, JobModerationService), not here.
public enum JobStatus {

    PENDING_APPROVAL("Pending approval"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    CLOSED("Closed");

    private final String label;

    JobStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
