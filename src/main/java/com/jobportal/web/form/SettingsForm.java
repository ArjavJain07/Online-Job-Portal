package com.jobportal.web.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

// Bound to the admin settings form (Section 7.5, A-F3). SettingsService.save copies a
// validated instance of this into the single SystemSettings row.
public class SettingsForm {

    @NotBlank(message = "Site name is required.")
    @Size(min = 2, max = 60, message = "Site name must be 2-60 characters.")
    private String siteName;

    @Size(max = 200, message = "Announcement must be at most 200 characters.")
    private String announcement;

    private boolean seekerRegistrationOpen;

    private boolean employerRegistrationOpen;

    private boolean jobApprovalRequired;

    @Min(value = 1, message = "Max active jobs per employer must be between 1 and 100.")
    @Max(value = 100, message = "Max active jobs per employer must be between 1 and 100.")
    private int maxActiveJobsPerEmployer;

    @Min(value = 1, message = "Max resume size must be between 1 and 5 MB.")
    @Max(value = 5, message = "Max resume size must be between 1 and 5 MB.")
    private int maxResumeSizeMb;

    @NotEmpty(message = "Choose at least one resume type.")
    private List<String> allowedResumeTypes;

    @Min(value = 5, message = "Items per page must be between 5 and 50.")
    @Max(value = 50, message = "Items per page must be between 5 and 50.")
    private int pageSize;

    @Min(value = 3, message = "Feed refresh interval must be between 3 and 60 seconds.")
    @Max(value = 60, message = "Feed refresh interval must be between 3 and 60 seconds.")
    private int feedRefreshSeconds;

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getAnnouncement() {
        return announcement;
    }

    public void setAnnouncement(String announcement) {
        this.announcement = announcement;
    }

    public boolean isSeekerRegistrationOpen() {
        return seekerRegistrationOpen;
    }

    public void setSeekerRegistrationOpen(boolean seekerRegistrationOpen) {
        this.seekerRegistrationOpen = seekerRegistrationOpen;
    }

    public boolean isEmployerRegistrationOpen() {
        return employerRegistrationOpen;
    }

    public void setEmployerRegistrationOpen(boolean employerRegistrationOpen) {
        this.employerRegistrationOpen = employerRegistrationOpen;
    }

    public boolean isJobApprovalRequired() {
        return jobApprovalRequired;
    }

    public void setJobApprovalRequired(boolean jobApprovalRequired) {
        this.jobApprovalRequired = jobApprovalRequired;
    }

    public int getMaxActiveJobsPerEmployer() {
        return maxActiveJobsPerEmployer;
    }

    public void setMaxActiveJobsPerEmployer(int maxActiveJobsPerEmployer) {
        this.maxActiveJobsPerEmployer = maxActiveJobsPerEmployer;
    }

    public int getMaxResumeSizeMb() {
        return maxResumeSizeMb;
    }

    public void setMaxResumeSizeMb(int maxResumeSizeMb) {
        this.maxResumeSizeMb = maxResumeSizeMb;
    }

    public List<String> getAllowedResumeTypes() {
        return allowedResumeTypes;
    }

    public void setAllowedResumeTypes(List<String> allowedResumeTypes) {
        this.allowedResumeTypes = allowedResumeTypes;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getFeedRefreshSeconds() {
        return feedRefreshSeconds;
    }

    public void setFeedRefreshSeconds(int feedRefreshSeconds) {
        this.feedRefreshSeconds = feedRefreshSeconds;
    }
}
