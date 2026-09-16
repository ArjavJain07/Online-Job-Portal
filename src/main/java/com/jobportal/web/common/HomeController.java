package com.jobportal.web.common;

import com.jobportal.security.AppUserDetails;
import com.jobportal.security.RoleRoutes;
import com.jobportal.service.JobSearchService;
import com.jobportal.service.SettingsService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    // Landing page shows its "latest openings" as at most this many job cards (P-1).
    private static final int LATEST_JOBS_LIMIT = 6;

    private final JobSearchService jobSearchService;
    private final SettingsService settingsService;

    public HomeController(JobSearchService jobSearchService, SettingsService settingsService) {
        this.jobSearchService = jobSearchService;
        this.settingsService = settingsService;
    }

    // GET /dashboard: sends a logged-in user to their own role dashboard (Section 4.4, 6.1
    // P-4). Used by the post-login redirect and the navbar's "My dashboard" link. Only
    // reachable while authenticated - SecurityConfig sends anonymous requests to /login.
    @GetMapping("/dashboard")
    public ResponseEntity<Void> dashboard(@AuthenticationPrincipal AppUserDetails me) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(RoleRoutes.dashboardUrl(me.getRole())))
                .build();
    }

    // GET /: the landing page (P-1), open to anyone - the route summary (6.6) marks it
    // "Anyone", not "anonymous only", so a logged-in visitor sees the same page with "Go
    // to your dashboard" in place of the registration cards (public/index.html decides
    // that from the global currentUser attribute) instead of being redirected away.
    // Everything shown here comes from live data: no hard-coded counts or job lists.
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("latestJobs", jobSearchService.latestLiveJobs(LATEST_JOBS_LIMIT));
        model.addAttribute("liveJobCount", jobSearchService.countLiveJobs());
        model.addAttribute("employersHiringCount", jobSearchService.countEmployersHiring());
        model.addAttribute("seekerRegistrationOpen", settingsService.get().isSeekerRegistrationOpen());
        model.addAttribute("employerRegistrationOpen", settingsService.get().isEmployerRegistrationOpen());
        return "public/index";
    }
}
