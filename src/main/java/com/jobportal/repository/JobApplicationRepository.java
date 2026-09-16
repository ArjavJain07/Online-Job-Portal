package com.jobportal.repository;

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.repository.projection.ApplicationStatusCount;
import com.jobportal.repository.projection.CategoryCount;
import com.jobportal.repository.projection.JobStatusPairCount;
import com.jobportal.repository.projection.TopJobCount;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {

    // Ownership (11.3 contract item 3).
    Optional<JobApplication> findByIdAndJob_Employer_Id(Long id, Long employerId);

    Optional<JobApplication> findByIdAndSeeker_Id(Long id, Long seekerId);

    // "One application per seeker per job" duplicate check (S-F2).
    Optional<JobApplication> findByJob_IdAndSeeker_Id(Long jobId, Long seekerId);

    // Dependency checks (Section 5.8: delete/role-change of a user or a job).
    long countByJob_Id(Long jobId);

    long countBySeeker_Id(Long seekerId);

    long countByJob_Employer_Id(Long employerId);

    // "Applied" badges on /seeker/jobs (Section 7.9): which of the jobs on this page has
    // the seeker already applied to, in one query.
    @Query("select a.job.id from JobApplication a where a.seeker.id = :seekerId and a.job.id in :jobIds")
    Set<Long> findJobIdsBySeekerAndJobIdIn(@Param("seekerId") Long seekerId, @Param("jobIds") Collection<Long> jobIds);

    // Seeker active applications (S-D2), not paginated (7.9 "four short lists").
    @EntityGraph(attributePaths = {"job", "job.employer"})
    List<JobApplication> findBySeeker_IdAndStatusIn(Long seekerId, Collection<ApplicationStatus> statuses, Sort sort);

    // Seeker application history (S-D4), same filter, paginated.
    @EntityGraph(attributePaths = {"job", "job.employer"})
    Page<JobApplication> findBySeeker_IdAndStatusIn(Long seekerId, Collection<ApplicationStatus> statuses, Pageable pageable);

    // Employer applications list (E-D2): jobId is optional (a foreign or unknown one is
    // ignored, Section 4.5), the status set is always passed ("Any" = every status).
    @EntityGraph(attributePaths = {"job", "seeker"})
    @Query("select a from JobApplication a where a.job.employer.id = :employerId "
            + "and (:jobId is null or a.job.id = :jobId) and a.status in :statuses")
    Page<JobApplication> findForEmployer(@Param("employerId") Long employerId, @Param("jobId") Long jobId,
            @Param("statuses") Collection<ApplicationStatus> statuses, Pageable pageable);

    // Timestamps for the applications-over-time chart (7.6): admin (all) and employer
    // (scoped to their own jobs, optionally one job) versions.
    @Query("select a.appliedAt from JobApplication a where a.appliedAt >= :from")
    List<LocalDateTime> findAppliedAtSince(@Param("from") LocalDateTime from);

    @Query("select a.appliedAt from JobApplication a where a.job.employer.id = :employerId "
            + "and (:jobId is null or a.job.id = :jobId) and a.appliedAt >= :from")
    List<LocalDateTime> findAppliedAtSince(@Param("employerId") Long employerId, @Param("jobId") Long jobId,
            @Param("from") LocalDateTime from);

    @Query("select j.category as category, count(a) as total from JobApplication a join a.job j "
            + "where a.appliedAt >= :from group by j.category order by count(a) desc")
    List<CategoryCount> countByCategorySince(@Param("from") LocalDateTime from);

    @Query("select a.status as status, count(a) as total from JobApplication a where a.appliedAt >= :from group by a.status")
    List<ApplicationStatusCount> countByStatusSince(@Param("from") LocalDateTime from);

    @Query("select a.status as status, count(a) as total from JobApplication a where a.job.employer.id = :employerId "
            + "and (:jobId is null or a.job.id = :jobId) and a.appliedAt >= :from group by a.status")
    List<ApplicationStatusCount> countByStatusSince(@Param("employerId") Long employerId, @Param("jobId") Long jobId,
            @Param("from") LocalDateTime from);

    // Per-job pivot for the E-D5 statistics table, pivoted into JobStatsRow in Java.
    @Query("select a.job.id as jobId, a.status as status, count(a) as total from JobApplication a "
            + "where a.job.employer.id = :employerId group by a.job.id, a.status")
    List<JobStatusPairCount> countByJobAndStatus(@Param("employerId") Long employerId);

    // Top 5 jobs by applications in range (admin statistics), limited with the Pageable.
    @Query("select a.job.id as jobId, a.job.title as title, a.job.employer.companyName as company, "
            + "count(a) as total from JobApplication a where a.appliedAt >= :from "
            + "group by a.job.id, a.job.title, a.job.employer.companyName order by count(a) desc")
    List<TopJobCount> findTopJobsSince(@Param("from") LocalDateTime from, Pageable pageable);

    @Query("select count(distinct a.seeker.id) from JobApplication a where a.appliedAt >= :from")
    long countDistinctSeekersSince(@Param("from") LocalDateTime from);
}
