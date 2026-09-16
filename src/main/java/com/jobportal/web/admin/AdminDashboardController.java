package com.jobportal.web.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// M3 replaces this placeholder with the real admin dashboard (KPI cards, pending-approvals
// mini table, live activity widget, latest-applications panel - Section 11.2). For M1 it
// only has to exist so the seeded admin account has somewhere to land after login.
@Controller
public class AdminDashboardController {

    @GetMapping("/admin/dashboard")
    public String dashboard() {
        return "admin/dashboard";
    }
}
