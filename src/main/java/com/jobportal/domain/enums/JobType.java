package com.jobportal.domain.enums;

// How a job is scheduled. Used on Job.jobType and SeekerProfile.preferredJobType.
public enum JobType {

    FULL_TIME("Full-time"),
    PART_TIME("Part-time"),
    INTERNSHIP("Internship"),
    CONTRACT("Contract");

    private final String label;

    JobType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
