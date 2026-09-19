package com.jobportal.repository;

import com.jobportal.domain.JobView;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobViewRepository extends JpaRepository<JobView, Long> {

    // Timestamps for the employer statistics "views over time" chart (Section 6.3 E-D5,
    // 7.6): scoped to one employer and, when the request narrows it, to one of their own
    // jobs - the same shape as JobApplicationRepository#findAppliedAtSince, so
    // EmployerStatisticsService can bucket both through the same DateBuckets.count call.
    @Query("select v.viewedAt from JobView v where v.job.employer.id = :employerId "
            + "and (:jobId is null or v.job.id = :jobId) and v.viewedAt >= :from")
    List<LocalDateTime> findViewedAtSince(@Param("employerId") Long employerId, @Param("jobId") Long jobId,
            @Param("from") LocalDateTime from);
}
