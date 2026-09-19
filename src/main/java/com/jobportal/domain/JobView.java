package com.jobportal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

// One recorded, human-looking view of a job's detail page - dated view analytics, the
// feature EmployerStatisticsService's "views over time" chart and view-to-application
// funnel are built from (Section 6.3 E-D5, 7.6).
//
// WHY THIS EXISTS NEXT TO Job.viewCount INSTEAD OF REPLACING IT
// viewCount is a single running total per job: cheap to read, already wired into every
// screen that shows "views" today (JobStatsRow's Views column, the E-D5 apply-rate table -
// Section 7.6 says so explicitly: "all time, because view counts are not dated"). It
// answers exactly one question, "how many views ever," and this feature does not need it
// to answer any other question, so V2 leaves it untouched.
//
// What a bare int cannot do is answer "when" - a trend line or a funnel scoped to the
// 7/30/90-day windows every other chart on the statistics pages already uses (DateBuckets,
// Section 7.6). That needs one row per counted view with a timestamp: this table. Nothing
// recomputes viewCount from a count of JobView rows, or the other way round - they are
// answers to two different questions, not two measurements of the same one, so there is
// nothing for them to drift out of sync with. See JobSearchService#recordView for exactly
// which views become a row here (deliberately a stricter, additive subset of the views
// that bump viewCount), and the feature's delivery report for the reasoning in full.
//
// Deliberately minimal: just the job and when. No session id, IP or user - nothing this
// feature's charts need, and one fewer thing to treat as personal data (7.6's own
// EngagementMetric table already reports rates and counts, never raw identifiers).
@Entity
@Table(name = "job_views")
public class JobView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @Column(nullable = false)
    private LocalDateTime viewedAt;

    // Fallback only - JobSearchService#recordView sets viewedAt explicitly from the
    // injected Clock so tests stay deterministic (Section 12.1, FixedClockConfig), the same
    // reasoning Job#prePersist and JobStatusChange#prePersist already give for their own
    // timestamps.
    @PrePersist
    void prePersist() {
        if (viewedAt == null) {
            viewedAt = LocalDateTime.now();
        }
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

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(LocalDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }
}
