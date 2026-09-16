package com.jobportal.domain.enums;

// Where the work happens.
public enum WorkMode {

    ONSITE("On-site"),
    REMOTE("Remote"),
    HYBRID("Hybrid");

    private final String label;

    WorkMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
