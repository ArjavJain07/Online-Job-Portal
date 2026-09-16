package com.jobportal.web.admin;

import com.jobportal.service.AdminStatisticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Admin job/application/user-engagement statistics (Section 6.2 A-D4, 7.6). Thin
// controller (11.3 contract item 1): every KPI, chart and table on the page is built by
// AdminStatisticsService, which is read-only and never calls ActivityLogService (viewing
// statistics changes nothing). "days" is forwarded exactly as it arrived, unparsed - it is
// bound as a plain String, not an int, so a non-numeric value (or a missing one) never
// reaches Spring's MethodArgumentTypeMismatchException 404 handler; DateBuckets.normaliseDays
// (called inside the service) is the only place that turns it into 7, 30 or 90 (Section 7.9
// binding rule, AC-A-D4-2).
@Controller
public class AdminStatisticsController {

    private final AdminStatisticsService adminStatisticsService;

    public AdminStatisticsController(AdminStatisticsService adminStatisticsService) {
        this.adminStatisticsService = adminStatisticsService;
    }

    @GetMapping("/admin/statistics")
    public String statistics(@RequestParam(required = false) String days, Model model) {
        model.addAttribute("stats", adminStatisticsService.getStatistics(days));
        return "admin/statistics";
    }
}
