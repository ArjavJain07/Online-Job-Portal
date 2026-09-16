package com.jobportal.service;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.ActivityLogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Records and serves the site-wide activity feed (Section 5.7, 7.7). Every business
// service calls log(...) inside its own transaction, so a rolled-back action is never
// recorded.
@Service
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final Clock clock;

    public ActivityLogService(ActivityLogRepository activityLogRepository, Clock clock) {
        this.activityLogRepository = activityLogRepository;
        this.clock = clock;
    }

    // Records one event with the current time. actor is null only for events with no
    // signed-in user (an anonymous failed login).
    @Transactional
    public void log(ActivityType type, User actor, String description, TargetType targetType, Long targetId) {
        logAt(type, actor, description, targetType, targetId, LocalDateTime.now(clock));
    }

    // Same as log(...), but with an explicit timestamp. Used only by DataSeeder to insert
    // back-dated demo events in time order, so ids increase with time (Section 7.7).
    @Transactional
    public void logAt(ActivityType type, User actor, String description, TargetType targetType, Long targetId,
            LocalDateTime at) {
        ActivityLog entry = new ActivityLog();
        entry.setType(type);
        entry.setActorId(actor != null ? actor.getId() : null);
        entry.setActorName(actor != null ? actorDisplayName(actor) : null);
        entry.setActorRole(actor != null ? actor.getRole() : null);
        entry.setDescription(description);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setIpAddress(currentIpAddress());
        entry.setCreatedAt(at);
        activityLogRepository.save(entry);
    }

    // ---- Feed and history queries (Section 7.7) ----

    // Polling endpoint (GET /admin/activity/feed): events strictly newer than the id the
    // client already has, oldest first within the batch so it can append in order.
    public List<ActivityLog> feedAfter(Long afterId) {
        return activityLogRepository.findTop50ByIdGreaterThanOrderByIdAsc(afterId);
    }

    // First render of /admin/activity.
    public List<ActivityLog> latest20() {
        return activityLogRepository.findTop20ByOrderByIdDesc();
    }

    // First render of the admin dashboard's live activity widget.
    public List<ActivityLog> latest10() {
        return activityLogRepository.findTop10ByOrderByIdDesc();
    }

    // First render of the "latest applications" panel (dashboard and activity page).
    public List<ActivityLog> latestApplications() {
        return activityLogRepository.findTop5ByTypeOrderByIdDesc(ActivityType.APPLICATION_SUBMITTED);
    }

    // Activity history table: every event, or only one type when a filter is chosen.
    public Page<ActivityLog> history(ActivityType type, Pageable pageable) {
        return type == null ? activityLogRepository.findAll(pageable) : activityLogRepository.findByType(type, pageable);
    }

    // "fullName (companyName)" for employers, the plain name otherwise (Section 5.2).
    private String actorDisplayName(User actor) {
        if (actor.getRole() == Role.EMPLOYER && actor.getCompanyName() != null) {
            return actor.getFullName() + " (" + actor.getCompanyName() + ")";
        }
        return actor.getFullName();
    }

    // Only present while an HTTP request is being handled; null during startup seeding.
    private String currentIpAddress() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest().getRemoteAddr() : null;
    }
}
