package com.jobportal.web.admin;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.dto.ActivityDto;
import com.jobportal.service.ActivityLogService;
import com.jobportal.service.SettingsService;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Live activity monitor (Section 6.2 A-D5, 7.7). The "live" bundle (the polling list, the
// latest-applications panel and the new-event counter) only makes sense against the
// unfiltered, first page of the feed - a filtered or paged view is a plain history query,
// so it renders only the history table (Section 6.2: "Live panel (page 0 with no type
// filter only)"). activity-feed.js itself also only does anything when it finds
// #activity-feed in the page (Section 7.7 client script), so leaving that element out on a
// filtered view is enough to disable polling there.
@Controller
public class AdminActivityController {

    private final ActivityLogService activityLogService;
    private final SettingsService settingsService;

    public AdminActivityController(ActivityLogService activityLogService, SettingsService settingsService) {
        this.activityLogService = activityLogService;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/activity")
    public String activity(@RequestParam(required = false) String type, @RequestParam(required = false) String page,
            Model model) {
        ActivityType selectedType = parseType(type);
        int pageNumber = parsePage(page);
        int pageSize = settingsService.get().getPageSize();
        int feedIntervalMs = settingsService.get().getFeedRefreshSeconds() * 1000;

        Page<ActivityLog> history = activityLogService.history(selectedType,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));

        boolean liveView = pageNumber == 0 && selectedType == null;
        model.addAttribute("liveView", liveView);
        model.addAttribute("historyPage", history.map(ActivityDto::from));
        model.addAttribute("selectedType", selectedType);
        model.addAttribute("types", ActivityType.values());
        model.addAttribute("feedIntervalMs", feedIntervalMs);

        if (liveView) {
            List<ActivityDto> liveEvents = activityLogService.latest20().stream().map(ActivityDto::from).toList();
            model.addAttribute("liveEvents", liveEvents);
            model.addAttribute("liveEventsLastId", lastId(liveEvents));
            model.addAttribute("latestApplications",
                    activityLogService.latestApplications().stream().map(ActivityDto::from).toList());
        }

        return "admin/activity";
    }

    // Query parameters that filter or page a list are bound as plain String and parsed
    // with a fallback here (Section 7.9 binding rule), so "type=NOPE" or "page=abc" never
    // reach the MethodArgumentTypeMismatchException 404 handler.
    private ActivityType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ActivityType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int parsePage(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(0, value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // The fragment's "lastId" (Section 7.7): the highest id among the events already
    // rendered, so the first poll only asks for events newer than what is on screen.
    // latest20() is newest-first, so that is simply the first row's id.
    private Long lastId(List<ActivityDto> events) {
        return events.isEmpty() ? 0L : events.get(0).id();
    }
}
