package com.jobportal.api;

import com.jobportal.dto.ActivityDto;
import com.jobportal.service.ActivityLogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The polling endpoint of Section 7.7 (A-D5). Deliberately outside com.jobportal.web:
// GlobalModelAttributes is a @ControllerAdvice scoped to basePackages = "com.jobportal.web",
// so keeping this controller in com.jobportal.api stops its navbar/announcement/sidebar
// queries from running on every single poll (every 3-60 seconds, per admin tab).
//
// Security is handled entirely by SecurityConfig: "/admin/**" requires ROLE_ADMIN, so a
// logged-in employer gets 403 and an anonymous XHR gets 401 (the X-Requested-With entry
// point) without any code here.
@RestController
public class AdminActivityFeedController {

    private final ActivityLogService activityLogService;

    public AdminActivityFeedController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    // GET /admin/activity/feed?afterId=87: up to 50 events with id > afterId, oldest first
    // within the batch. afterId is bound as a String and parsed leniently (Section 7.9
    // binding rule): missing, negative or non-numeric all mean "start from the beginning".
    @GetMapping("/admin/activity/feed")
    public List<ActivityDto> feed(@RequestParam(required = false) String afterId) {
        return activityLogService.feedAfter(parseAfterId(afterId)).stream().map(ActivityDto::from).toList();
    }

    private Long parseAfterId(String raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value < 0 ? 0L : value;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
