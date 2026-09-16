package com.jobportal.repository;

import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.enums.JobStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobStatusChangeRepository extends JpaRepository<JobStatusChange, Long> {

    // The job's status timeline, oldest first (admin job review, employer job detail).
    List<JobStatusChange> findByJob_IdOrderByChangedAtAsc(Long jobId);

    // Step 1 of the approval-turnaround statistic (7.6): decisions made in the range.
    List<JobStatusChange> findByToStatusInAndChangedAtGreaterThanEqual(Collection<JobStatus> toStatuses, LocalDateTime from);

    // Step 2 of the approval-turnaround statistic: every status row of those jobs, no
    // date filter, so the preceding PENDING_APPROVAL row can be found even if it is
    // older than the window. Paired with its decision in Java, not SQL.
    List<JobStatusChange> findByJob_IdInOrderByJob_IdAscChangedAtAsc(Collection<Long> jobIds);
}
