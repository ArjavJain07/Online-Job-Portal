package com.jobportal.domain.enums;

// What an ActivityLog row points at, used only to build the "Link" column in the activity
// history (5.7). Never shown to the user as text, so it carries no label.
public enum TargetType {
    USER,
    JOB,
    APPLICATION,
    SETTINGS
}
