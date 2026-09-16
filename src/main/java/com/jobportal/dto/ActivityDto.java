package com.jobportal.dto;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.enums.TargetType;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

// The shape of one activity row, sent as JSON by the polling feed (Section 7.7) and reused
// server-side for the first render of the live widgets (admin/dashboard, admin/activity)
// and the history table, so an event looks the same whether it was rendered by Thymeleaf or
// appended later by activity-feed.js. Exact field list and JSON shape from Section 7.7:
// {"id":88,"type":"APPLICATION_SUBMITTED","typeLabel":"Application submitted",
//  "actorName":"Priya Sharma","actorRole":"JOB_SEEKER","description":"...",
//  "link":null,"createdAt":"2026-09-16T10:42:05","timeLabel":"16 Sep, 10:42"}
public record ActivityDto(Long id, String type, String typeLabel, String actorName, String actorRole,
        String description, String link, String createdAt, String timeLabel) {

    private static final DateTimeFormatter TIME_LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    public static ActivityDto from(ActivityLog log) {
        return new ActivityDto(
                log.getId(),
                log.getType().name(),
                log.getType().getLabel(),
                log.getActorName(),
                log.getActorRole() == null ? null : log.getActorRole().name(),
                log.getDescription(),
                link(log),
                log.getCreatedAt().toString(),
                log.getCreatedAt().format(TIME_LABEL_FORMAT));
    }

    // Link column (6.2 A-D5): USER opens the edit form, JOB opens the admin review page,
    // SETTINGS opens the settings page; APPLICATION and untargeted events (a failed login,
    // a deleted user) carry no link, admins do not open applications from here.
    private static String link(ActivityLog log) {
        TargetType targetType = log.getTargetType();
        Long targetId = log.getTargetId();
        if (targetType == null || targetId == null) {
            return null;
        }
        return switch (targetType) {
            case USER -> "/admin/users/" + targetId + "/edit";
            case JOB -> "/admin/jobs/" + targetId;
            case SETTINGS -> "/admin/settings";
            case APPLICATION -> null;
        };
    }
}
