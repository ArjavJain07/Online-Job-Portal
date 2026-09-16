package com.jobportal.domain.enums;

// The three account types. Stored as a STRING column so the database stays readable.
public enum Role {

    ADMIN("Admin"),
    EMPLOYER("Employer"),
    JOB_SEEKER("Job Seeker");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
