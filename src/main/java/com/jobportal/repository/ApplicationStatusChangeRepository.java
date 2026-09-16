package com.jobportal.repository;

import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.projection.FirstResponseRow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationStatusChangeRepository extends JpaRepository<ApplicationStatusChange, Long> {

    // The application's status timeline, oldest first (seeker and employer detail pages).
    List<ApplicationStatusChange> findByApplication_IdOrderByChangedAtAsc(Long applicationId);

    // Hires in range (admin engagement metrics, 7.6).
    long countByToStatusAndChangedAtGreaterThanEqual(ApplicationStatus toStatus, LocalDateTime from);

    // First employer response per application (E-D5 first-response metric, 7.6): the
    // earliest status change the employer made on each application applied for in range.
    @Query("select c.application.id as applicationId, c.application.appliedAt as appliedAt, "
            + "min(c.changedAt) as firstChange "
            + "from ApplicationStatusChange c "
            + "where c.application.job.employer.id = :employerId and c.actorRole = :actorRole "
            + "and c.application.appliedAt >= :from "
            + "group by c.application.id, c.application.appliedAt")
    List<FirstResponseRow> findFirstResponses(@Param("employerId") Long employerId,
            @Param("actorRole") Role actorRole, @Param("from") LocalDateTime from);
}
