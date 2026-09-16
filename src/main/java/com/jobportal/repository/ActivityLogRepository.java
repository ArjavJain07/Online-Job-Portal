package com.jobportal.repository;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.enums.ActivityType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    // Feed polling (7.7): events after the last id the client already has, oldest first
    // within the batch so the client appends in order.
    List<ActivityLog> findTop50ByIdGreaterThanOrderByIdAsc(Long afterId);

    // First render of /admin/activity (7.7).
    List<ActivityLog> findTop20ByOrderByIdDesc();

    // First render of the dashboard's live activity widget (DASH-A).
    List<ActivityLog> findTop10ByOrderByIdDesc();

    // First render of the "latest applications" panel (DASH-A, 7.7).
    List<ActivityLog> findTop5ByTypeOrderByIdDesc(ActivityType type);

    // Activity history table (/admin/activity), filtered by type or showing everything
    // (findAll(pageable) is inherited from JpaRepository).
    Page<ActivityLog> findByType(ActivityType type, Pageable pageable);

    // A-D4 logins-over-time engagement chart.
    @Query("select l.createdAt from ActivityLog l where l.type = :type and l.createdAt >= :from")
    List<LocalDateTime> findCreatedAtByTypeSince(@Param("type") ActivityType type, @Param("from") LocalDateTime from);
}
