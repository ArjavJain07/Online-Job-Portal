package com.jobportal.web.seeker;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// M7 replaces this placeholder with the real seeker dashboard (application summary,
// recommended jobs - Section 11.2). For M1 it only has to exist so a seeded job seeker
// account has somewhere to land after login.
@Controller
public class SeekerDashboardController {

    @GetMapping("/seeker/dashboard")
    public String dashboard() {
        return "seeker/dashboard";
    }
}
