package com.jobportal.domain;

import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobDisplayStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// One job posting. The stored lifecycle is JobStatus (Section 5.5); Live, Expired and
// Hidden are computed labels, never stored - see displayStatus(LocalDate).
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employer_id")
    private User employer;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    @Column(nullable = false, length = 2000)
    private String requirements;

    @Column(nullable = false, length = 400)
    private String skills;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkMode workMode;

    @Column(nullable = false, length = 100)
    private String location;

    @Column(nullable = false)
    private int salaryMin;

    @Column(nullable = false)
    private int salaryMax;

    @Column(nullable = false)
    private int minExperienceYears;

    @Column(nullable = false)
    private int openings = 1;

    @Column(nullable = false)
    private LocalDate applicationDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 500)
    private String rejectionReason;

    @Column(nullable = false)
    private int viewCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime closedAt;

    // DataSeeder sets back-dated createdAt values before saving; this only fills it in
    // when a normal save left it null (mapping rule, 5.3).
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // A job is Live when it is approved, its deadline has not passed and the employer
    // account is still enabled. Defined once here and mirrored in JobSpecifications.live
    // (1.4 glossary).
    public boolean isLive(LocalDate today) {
        return status == JobStatus.APPROVED
                && !applicationDeadline.isBefore(today)
                && employer.isEnabled();
    }

    // The status shown to users (table in Section 5.5). Hidden takes precedence over
    // Expired, and both are computed only when the stored status is APPROVED.
    public JobDisplayStatus displayStatus(LocalDate today) {
        if (status == JobStatus.APPROVED) {
            if (!employer.isEnabled()) {
                return JobDisplayStatus.HIDDEN;
            }
            if (applicationDeadline.isBefore(today)) {
                return JobDisplayStatus.EXPIRED;
            }
            return JobDisplayStatus.LIVE;
        }
        return switch (status) {
            case PENDING_APPROVAL -> JobDisplayStatus.PENDING_APPROVAL;
            case REJECTED -> JobDisplayStatus.REJECTED;
            case CLOSED -> JobDisplayStatus.CLOSED;
            default -> throw new IllegalStateException("Unhandled job status: " + status);
        };
    }

    // Counts toward an employer's posting limit: still awaiting a decision or currently
    // live/expired/hidden (1.4 glossary, "Active job").
    public boolean isActive() {
        return status == JobStatus.PENDING_APPROVAL || status == JobStatus.APPROVED;
    }

    // The normalised skills CSV as a list, in the order they were entered.
    public List<String> skillList() {
        List<String> result = new ArrayList<>();
        if (skills == null || skills.isBlank()) {
            return result;
        }
        for (String skill : skills.split(",")) {
            String trimmed = skill.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getEmployer() {
        return employer;
    }

    public void setEmployer(User employer) {
        this.employer = employer;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public JobCategory getCategory() {
        return category;
    }

    public void setCategory(JobCategory category) {
        this.category = category;
    }

    public JobType getJobType() {
        return jobType;
    }

    public void setJobType(JobType jobType) {
        this.jobType = jobType;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public void setWorkMode(WorkMode workMode) {
        this.workMode = workMode;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(int salaryMin) {
        this.salaryMin = salaryMin;
    }

    public int getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(int salaryMax) {
        this.salaryMax = salaryMax;
    }

    public int getMinExperienceYears() {
        return minExperienceYears;
    }

    public void setMinExperienceYears(int minExperienceYears) {
        this.minExperienceYears = minExperienceYears;
    }

    public int getOpenings() {
        return openings;
    }

    public void setOpenings(int openings) {
        this.openings = openings;
    }

    public LocalDate getApplicationDeadline() {
        return applicationDeadline;
    }

    public void setApplicationDeadline(LocalDate applicationDeadline) {
        this.applicationDeadline = applicationDeadline;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public int getViewCount() {
        return viewCount;
    }

    public void setViewCount(int viewCount) {
        this.viewCount = viewCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
