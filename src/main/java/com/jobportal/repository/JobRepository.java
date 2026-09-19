package com.jobportal.repository;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.repository.projection.JobStatusCount;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// JobSkillFacets is a custom fragment (Section 10.8), not a derived or @Query method: the
// skill facet counts have to be built from the same Specification the search itself uses,
// which only the Criteria API can do. See that interface for why.
public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job>, JobSkillFacets {

    // Redeclared with @EntityGraph so each job's employer loads in the same query
    // (Section 7.9); safe with paging because employer is a to-one association.
    @Override
    @EntityGraph(attributePaths = "employer")
    Page<Job> findAll(Specification<Job> spec, Pageable pageable);

    // Ownership (11.3 contract item 3): an employer's own job pages.
    Optional<Job> findByIdAndEmployer_Id(Long id, Long employerId);

    // Used by the delete/role-change dependency checks of Section 5.8.
    long countByEmployer_Id(Long employerId);

    @Query("select j.createdAt from Job j where j.createdAt >= :from")
    List<LocalDateTime> findCreatedAtSince(@Param("from") LocalDateTime from);

    @Query("select j.status as status, count(j) as total from Job j group by j.status")
    List<JobStatusCount> countGroupedByStatus();

    @Query("select count(distinct j.employer.id) from Job j where j.createdAt >= :from")
    long countDistinctEmployersSince(@Param("from") LocalDateTime from);

    // Public landing page counter, "N companies hiring" (P-1): employers with at least
    // one Live job right now.
    @Query("select count(distinct j.employer.id) from Job j "
            + "where j.status = :status and j.applicationDeadline >= :today and j.employer.enabled = true")
    long countDistinctEmployersWithLiveJobs(@Param("status") JobStatus status, @Param("today") LocalDate today);

    // Employer statistics, total views across all of the employer's jobs (E-D5).
    @Query("select coalesce(sum(j.viewCount), 0) from Job j where j.employer.id = :employerId")
    long sumViewCountByEmployer(@Param("employerId") Long employerId);

    // Public job detail view counting (P-2): plain @Modifying, no clearAutomatically
    // (Section 7.10).
    @Modifying
    @Query("update Job j set j.viewCount = j.viewCount + 1 where j.id = :id")
    void incrementViewCount(@Param("id") Long id);
}
