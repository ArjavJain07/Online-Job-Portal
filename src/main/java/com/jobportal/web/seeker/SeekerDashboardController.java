package com.jobportal.web.seeker;

import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobApplicationService;
import com.jobportal.service.RecommendationService;
import com.jobportal.service.SeekerProfileService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// Job seeker dashboard home (Section 6.4 DASH-S, 11.2 M7). Thin controller (11.3 contract
// item 1): the KPI counts, the completeness bar and the recommendations are every one of
// them owned elsewhere (JobApplicationService, SeekerProfileService, RecommendationService)
// - this class only reads them and builds the small "recent updates" list the plan asks
// for, which no other page already exposes.
@Controller
public class SeekerDashboardController {

    // Section 6.4 DASH-S "Recommended for you": top 6 on the dashboard, 20 on the full
    // page (S-D5, 7.8 step 5) - SeekerJobController#recommendations uses the other limit.
    private static final int DASHBOARD_RECOMMENDATION_LIMIT = 6;
    // Section 6.4 DASH-S "Recent updates": "latest 5 employer status changes".
    private static final int RECENT_UPDATES_LIMIT = 5;

    private final JobApplicationService jobApplicationService;
    private final SeekerProfileService seekerProfileService;
    private final RecommendationService recommendationService;

    public SeekerDashboardController(JobApplicationService jobApplicationService,
            SeekerProfileService seekerProfileService, RecommendationService recommendationService) {
        this.jobApplicationService = jobApplicationService;
        this.seekerProfileService = seekerProfileService;
        this.recommendationService = recommendationService;
    }

    @GetMapping("/seeker/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        Long seekerId = me.getId();

        // KPI row (AC-DS-1): Active applications and Interviews are the same unfiltered
        // chip counts S-D2's own list computes; Hired is counted the exact same way S-D4
        // counts it (a HIRED-only, every-status history page) rather than a new query -
        // 11.3 contract item 5, never re-derive a rule a service already owns. Unread
        // messages is left to the global "unreadMessageCount" model attribute
        // (GlobalModelAttributes): this page never marks a thread read, so it is never
        // overwritten (Section 7.10).
        JobApplicationService.SeekerActiveApplications active =
                jobApplicationService.activeApplicationsForSeeker(seekerId, "ALL");
        JobApplicationService.SeekerApplicationHistory hired =
                jobApplicationService.applicationHistoryForSeeker(seekerId, "HIRED", "all", "0");
        model.addAttribute("activeApplicationsCount", active.totalActive());
        model.addAttribute("interviewsCount", active.interviewCount());
        model.addAttribute("hiredCount", hired.page().getTotalElements());

        // Profile completeness (S-F4/S-D3 weights): hidden at 100% (Section 6.4 DASH-S),
        // so the template checks completeness.percent itself.
        model.addAttribute("completeness",
                seekerProfileService.completeness(seekerProfileService.loadProfile(seekerId)));

        // Recent updates: the 5 most recent EMPLOYER-made status changes across every one
        // of the seeker's applications (Section 6.4 DASH-S screen example).
        model.addAttribute("recentUpdates", recentEmployerUpdates(seekerId));

        // Recommended for you (S-D5, 7.8): top 6, or the "Latest jobs" fallback - the
        // template reads result.fallback() to choose the heading/prompt.
        RecommendationResult recommendations = recommendationService.recommend(seekerId, DASHBOARD_RECOMMENDATION_LIMIT);
        model.addAttribute("recommendations", recommendations);

        return "seeker/dashboard";
    }

    // Section 6.4 DASH-S "Recent updates": walks every one of the seeker's applications
    // (S-D4's own "view=all, every status" page, read unpaginated like every other
    // dashboard widget - Section 6.0/7.9) and their full timelines, keeps only the rows
    // an employer actually made (never the seeker's own "Applied" row or their own
    // withdrawal), and returns the newest 5.
    private List<ApplicationStatusChange> recentEmployerUpdates(Long seekerId) {
        JobApplicationService.SeekerApplicationHistory all =
                jobApplicationService.applicationHistoryForSeeker(seekerId, "ALL", "all", "0");
        List<ApplicationStatusChange> updates = new ArrayList<>();
        for (JobApplication application : all.page().getContent()) {
            for (ApplicationStatusChange change : jobApplicationService.timeline(application.getId())) {
                if (change.getActorRole() == Role.EMPLOYER) {
                    updates.add(change);
                }
            }
        }
        updates.sort(Comparator.comparing(ApplicationStatusChange::getChangedAt).reversed());
        return updates.size() > RECENT_UPDATES_LIMIT ? updates.subList(0, RECENT_UPDATES_LIMIT) : updates;
    }
}
