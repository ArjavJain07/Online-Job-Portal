package com.jobportal.domain;

import com.jobportal.domain.enums.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

// One seeker's application to one job. A seeker can apply to a job at most once
// (uk_application_job_seeker). The stored lifecycle is ApplicationStatus (Section 5.6).
@Entity
@Table(name = "job_applications",
        uniqueConstraints = @UniqueConstraint(name = "uk_application_job_seeker",
                columnNames = {"job_id", "seeker_id"}))
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seeker_id")
    private User seeker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status;

    @Column(length = 3000)
    private String coverLetter;

    @Column(nullable = false, length = 60)
    private String resumeStoredName;

    @Column(nullable = false, length = 150)
    private String resumeOriginalName;

    @Column(nullable = false, length = 100)
    private String resumeContentType;

    @Column(nullable = false)
    private long resumeSizeBytes;

    @Column(length = 1000)
    private String internalNote;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    // Set on every status change after APPLIED (employer changes and seeker
    // withdrawal). Stays null while still APPLIED. Deliberately separate from
    // updatedAt - see the note on isUpdatedForSeeker() below.
    private LocalDateTime statusChangedAt;

    private LocalDateTime seekerLastViewedAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
    }

    // Human-readable application number shown throughout the UI, e.g. "APP-00042".
    public String getReference() {
        return String.format("APP-%05d", id);
    }

    // True when the employer (or the seeker's own withdrawal) changed the status more
    // recently than the seeker last opened the detail page. Comparing against
    // statusChangedAt rather than updatedAt matters: opening the page only writes
    // seekerLastViewedAt, so if the badge compared updatedAt it would never clear.
    public boolean isUpdatedForSeeker() {
        return statusChangedAt != null
                && (seekerLastViewedAt == null || statusChangedAt.isAfter(seekerLastViewedAt));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public User getSeeker() {
        return seeker;
    }

    public void setSeeker(User seeker) {
        this.seeker = seeker;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public String getCoverLetter() {
        return coverLetter;
    }

    public void setCoverLetter(String coverLetter) {
        this.coverLetter = coverLetter;
    }

    public String getResumeStoredName() {
        return resumeStoredName;
    }

    public void setResumeStoredName(String resumeStoredName) {
        this.resumeStoredName = resumeStoredName;
    }

    public String getResumeOriginalName() {
        return resumeOriginalName;
    }

    public void setResumeOriginalName(String resumeOriginalName) {
        this.resumeOriginalName = resumeOriginalName;
    }

    public String getResumeContentType() {
        return resumeContentType;
    }

    public void setResumeContentType(String resumeContentType) {
        this.resumeContentType = resumeContentType;
    }

    public long getResumeSizeBytes() {
        return resumeSizeBytes;
    }

    public void setResumeSizeBytes(long resumeSizeBytes) {
        this.resumeSizeBytes = resumeSizeBytes;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public void setInternalNote(String internalNote) {
        this.internalNote = internalNote;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(LocalDateTime statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public LocalDateTime getSeekerLastViewedAt() {
        return seekerLastViewedAt;
    }

    public void setSeekerLastViewedAt(LocalDateTime seekerLastViewedAt) {
        this.seekerLastViewedAt = seekerLastViewedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
