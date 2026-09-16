package com.jobportal.domain;

import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
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
import java.time.LocalDateTime;

// One row of a job's status timeline (Section 5.5). actorName and actorRole are copied
// at the time of the change, not a foreign key, so the timeline still reads correctly
// after the actor is renamed or deleted.
@Entity
@Table(name = "job_status_changes")
public class JobStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private JobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus toStatus;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 230)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role actorRole;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void prePersist() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    // The label shown on the timeline (Section 5.5 "Timeline label" column) - distinct
    // from the plain toStatus badge fragments/status-badge already renders on jobs.html/
    // job-history.html, because several different (fromStatus, toStatus) pairs share the
    // same toStatus but mean different things to the reader: "Posted" vs "Resubmitted" vs
    // "Reopened, awaiting approval" are all a move INTO PENDING_APPROVAL, and "Approved"
    // vs "Reopened" are both a move into APPROVED. Every pair in the 5.5 transition table
    // maps to exactly one label, so this needs only fromStatus and toStatus.
    public String getTimelineLabel() {
        if (fromStatus == null) {
            return toStatus == JobStatus.APPROVED ? "Posted and auto-approved" : "Posted";
        }
        if (toStatus == JobStatus.CLOSED) {
            return "Closed";
        }
        return switch (fromStatus) {
            case PENDING_APPROVAL -> toStatus == JobStatus.APPROVED ? "Approved" : "Rejected";
            case APPROVED -> toStatus == JobStatus.REJECTED ? "Taken down" : "Edited, awaiting re-approval";
            case REJECTED -> "Resubmitted";
            case CLOSED -> toStatus == JobStatus.APPROVED ? "Reopened" : "Reopened, awaiting approval";
        };
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

    public JobStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(JobStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public JobStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(JobStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public Role getActorRole() {
        return actorRole;
    }

    public void setActorRole(Role actorRole) {
        this.actorRole = actorRole;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
