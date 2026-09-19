package com.jobportal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

// A job seeker's bookmark on one job (Section 16 future-work item 5: "saved or bookmarked
// jobs", now built). One row per (seeker, job) pair - the unique constraint below is the
// same "one X per seeker per job" shape as JobApplication.uk_application_job_seeker, and
// is named explicitly for the same reason SavedJobRepositoryTest (and this class's own
// service, SavedJobService) can assert on it (10.7: "name new constraints properly").
//
// Deliberately its OWN table rather than a field on SeekerProfile or User: a save is a
// per-(seeker, job) fact, not a per-seeker one, so it cannot live as a column on either
// entity at all - the same reasoning that already gave JobApplication (per seeker+job) and
// JobView (per job) their own tables instead of growing SeekerProfile/Job.
@Entity
@Table(name = "saved_jobs", uniqueConstraints = @UniqueConstraint(name = "uk_saved_job_seeker_job",
        columnNames = {"seeker_id", "job_id"}))
public class SavedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seeker_id", nullable = false)
    private User seeker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getSeeker() {
        return seeker;
    }

    public void setSeeker(User seeker) {
        this.seeker = seeker;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public LocalDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(LocalDateTime savedAt) {
        this.savedAt = savedAt;
    }
}
