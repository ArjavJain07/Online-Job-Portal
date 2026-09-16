package com.jobportal.web.employer;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// M7 replaces this placeholder with the real employer dashboard (KPIs, recent
// applications, recommendations link - Section 11.2). For M1 it only has to exist so a
// seeded employer account has somewhere to land after login.
@Controller
public class EmployerDashboardController {

    @GetMapping("/employer/dashboard")
    public String dashboard() {
        return "employer/dashboard";
    }
}
