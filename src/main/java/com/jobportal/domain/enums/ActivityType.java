package com.jobportal.domain.enums;

// The catalogue of events recorded in ActivityLog (Section 5.7). Used to filter the
// admin activity history and to label rows with getLabel().
public enum ActivityType {

    USER_REGISTERED("User registered"),
    LOGIN_SUCCESS("Login success"),
    LOGIN_FAILED("Login failed"),
    PASSWORD_CHANGED("Password changed"),
    USER_CREATED("User created"),
    USER_UPDATED("User updated"),
    USER_STATUS_CHANGED("User status changed"),
    USER_DELETED("User deleted"),
    JOB_POSTED("Job posted"),
    JOB_UPDATED("Job updated"),
    JOB_APPROVED("Job approved"),
    JOB_REJECTED("Job rejected"),
    JOB_TAKEN_DOWN("Job taken down"),
    JOB_CLOSED("Job closed"),
    JOB_REOPENED("Job reopened"),
    JOB_DELETED("Job deleted"),
    APPLICATION_SUBMITTED("Application submitted"),
    APPLICATION_STATUS_CHANGED("Application status changed"),
    APPLICATION_WITHDRAWN("Application withdrawn"),
    MESSAGE_SENT("Message sent"),
    PROFILE_UPDATED("Profile updated"),
    RESUME_UPLOADED("Resume uploaded"),
    SETTINGS_UPDATED("Settings updated");

    private final String label;

    ActivityType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
