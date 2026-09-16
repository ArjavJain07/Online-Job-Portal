package com.jobportal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

// The single row of site-wide settings (Section 7.5), always id = 1. The id is
// assigned, not generated, by DataSeeder; SettingsService.get() reads it fresh on every
// call and it is never cached (7.5) so a rolled-back save can never leave stale values
// in memory.
@Entity
@Table(name = "system_settings")
public class SystemSettings {

    @Id
    private Long id;

    @Column(nullable = false, length = 60)
    private String siteName = "JobPortal";

    @Column(length = 200)
    private String announcement = "";

    @Column(nullable = false)
    private boolean seekerRegistrationOpen = true;

    @Column(nullable = false)
    private boolean employerRegistrationOpen = true;

    @Column(nullable = false)
    private boolean jobApprovalRequired = true;

    @Column(nullable = false)
    private int maxActiveJobsPerEmployer = 20;

    @Column(nullable = false)
    private int maxResumeSizeMb = 2;

    @Column(nullable = false, length = 50)
    private String allowedResumeTypes = "pdf,doc,docx";

    @Column(nullable = false)
    private int pageSize = 10;

    @Column(nullable = false)
    private int feedRefreshSeconds = 5;

    private LocalDateTime updatedAt;

    @Column(length = 100)
    private String updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getAllowedResumeTypes() {
        return allowedResumeTypes;
    }

    public void setAllowedResumeTypes(String allowedResumeTypes) {
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
