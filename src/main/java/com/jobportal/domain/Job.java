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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

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

    // The skills this job asks for, as shared Skill rows (Section 10.8). This is the
    // source of truth: everything that matches, filters, facets or displays a job's
    // skills reads this list, never the legacySkills column below.
    //
    // A List with @OrderColumn rather than a Set, because a job's skills are shown in the
    // order the employer typed them ("Java, Spring Boot, SQL", not the alphabetical
    // "Java, SQL, Spring Boot") and that order is part of what the employer wrote. The
    // list is always replaced wholesale by assignSkills, so @OrderColumn's usual hazard -
    // a gap left behind by removing one element from the middle - cannot arise here.
    //
    // @BatchSize because of RecommendationService: it scores up to 200 candidate jobs in
    // one request (7.8 step 3) and RecommendationScorer reads every one of their skill
    // lists, which as a plain lazy collection is 200 extra round trips where the old CSV
    // column was free. Batching turns those into four. An @EntityGraph on the paged
    // finder would be the other way to do it and is the wrong one: eager-fetching a
    // to-many alongside a Pageable makes Hibernate fetch every row and paginate in
    // memory, which is a much worse trade than four queries.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "job_skills",
            joinColumns = @JoinColumn(name = "job_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    @OrderColumn(name = "display_order")
    @BatchSize(size = 50)
    private List<Skill> skills = new ArrayList<>();

    // The pre-Section-10.8 comma-separated column, kept for exactly one reason: rollback.
    // Downgrading to the release before skills became an entity must find this column
    // populated, or every job on the site loses its skills. It is written by assignSkills
    // and read by NOTHING in the running application - which is what makes keeping it
    // safe rather than a second source of truth free to drift. There is no public
    // accessor for the same reason; the test that proves it is still maintained reaches
    // it through legacySkillsCsv().
    //
    // Rows not saved since the V4 migration still hold the exact text their employer
    // typed, which can differ in spelling (never in meaning) from the labels of the Skill
    // rows above. That is harmless precisely because nothing reads it, and it is better
    // than a migration that rewrites text a person entered.
    @Column(name = "skills", nullable = false, length = 400)
    private String legacySkills = "";

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

    // The job's skills as display labels, in the order the employer entered them - what
    // every template renders as chips (Section 7.1 core rule 6) and what the employer's
    // edit form is pre-filled with.
    public List<String> skillList() {
        List<String> result = new ArrayList<>();
        for (Skill skill : skills) {
            result.add(skill.getLabel());
        }
        return result;
    }

    public List<Skill> getSkills() {
        return List.copyOf(skills);
    }

    // The ONLY way a job's skills change (Section 10.8). It writes the relation and the
    // legacySkills rollback column from the same list, so the two cannot disagree about
    // which skills a job has. Callers get their Skill rows from SkillService, which is
    // what turns the employer's free text into shared rows.
    public void assignSkills(List<Skill> newSkills) {
        skills.clear();
        skills.addAll(newSkills);
        List<String> labels = new ArrayList<>();
        for (Skill skill : newSkills) {
            labels.add(skill.getLabel());
        }
        legacySkills = String.join(", ", labels);
    }

    // The rollback column's raw content. Package-private: only the test that proves the
    // column is still being maintained has any business reading it.
    String legacySkillsCsv() {
        return legacySkills;
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
