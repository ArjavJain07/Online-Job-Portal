package com.jobportal.web.employer;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.dto.EmployerStatistics;
import com.jobportal.dto.KpiValue;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.EmployerStatisticsService;
import com.jobportal.service.JobApplicationService;
import com.jobportal.service.JobService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// Employer dashboard home (Section 6.3 DASH-E, 11.2 M7). Thin controller (11.3 contract
// item 1): every figure here is read straight off JobService, JobApplicationService or
// EmployerStatisticsService - none of it is a rule this page recomputes for itself
// (contract item 5), so the KPI row can never drift from what My Jobs (E-D1) or
// Statistics (E-D5) show for the same employer.
@Controller
public class EmployerDashboardController {

    // Section 6.3 DASH-E screen: "Recent applications: last 5".
    private static final int RECENT_APPLICATIONS_LIMIT = 5;
    // Section 6.3 DASH-E "Needs attention": "applications waiting in APPLIED for more
    // than 7 days".
    private static final int STALE_APPLICATION_DAYS = 7;

    private final JobService jobService;
    private final JobApplicationService jobApplicationService;
    private final EmployerStatisticsService employerStatisticsService;
    private final Clock clock;

    public EmployerDashboardController(JobService jobService, JobApplicationService jobApplicationService,
            EmployerStatisticsService employerStatisticsService, Clock clock) {
        this.jobService = jobService;
        this.jobApplicationService = jobApplicationService;
        this.employerStatisticsService = employerStatisticsService;
        this.clock = clock;
    }

    @GetMapping("/employer/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        Long employerId = me.getId();
        LocalDate today = LocalDate.now(clock);

        // KPI cards "Live jobs" / "Pending approval" and the "Needs attention" rejected/
        // expired lists all come from the same PENDING_APPROVAL + APPROVED + REJECTED
        // list My Jobs (E-D1) already builds, walked once here. A job is bucketed by
        // Job.isLive(today) (11.3 contract item 4) first, so "expired" only ever means
        // "APPROVED but isLive is false" - never a second, hand-written copy of the Live
        // rule.
        JobService.EmployerJobList employerJobs = jobService.employerJobs(employerId);
        long liveJobs = 0;
        long pendingApproval = 0;
        List<Job> rejectedJobs = new ArrayList<>();
        List<Job> expiredJobs = new ArrayList<>();
        for (Job job : employerJobs.jobs()) {
            if (job.isLive(today)) {
                liveJobs++;
            } else if (job.getStatus() == JobStatus.PENDING_APPROVAL) {
                pendingApproval++;
            } else if (job.getStatus() == JobStatus.REJECTED) {
                rejectedJobs.add(job);
            } else if (job.getStatus() == JobStatus.APPROVED) {
                expiredJobs.add(job); // APPROVED but not Live => the deadline has passed
            }
        }
        model.addAttribute("liveJobs", liveJobs);
        model.addAttribute("pendingApproval", pendingApproval);
        model.addAttribute("rejectedJobs", rejectedJobs);
        model.addAttribute("expiredJobs", expiredJobs);

        // "New applications" is the exact "Awaiting review" figure the E-D5 statistics
        // KPI row computes for the default 30-day range (do not recompute the APPLIED
        // count by hand here, Section 6.3 note on this page); the same call also returns
        // the "applications per day" chart this page shows, so both stay in sync.
        EmployerStatistics stats = employerStatisticsService.getStatistics(employerId, "30", null);
        model.addAttribute("stats", stats);
        model.addAttribute("newApplications", awaitingReviewCount(stats));

        // "Needs attention" third bullet: applications still APPLIED after a week. Reuses
        // E-D2's own "Applied" queue (Section 6.3) rather than a new query.
        LocalDateTime staleCutoff = LocalDateTime.now(clock).minusDays(STALE_APPLICATION_DAYS);
        List<JobApplication> awaitingReview =
                jobApplicationService.listForEmployer(employerId, null, "APPLIED", "0").applications().getContent();
        List<JobApplication> staleApplications = new ArrayList<>();
        for (JobApplication application : awaitingReview) {
            if (application.getAppliedAt().isBefore(staleCutoff)) {
                staleApplications.add(application);
            }
        }
        model.addAttribute("staleApplications", staleApplications);

        // Recent applications: last 5, off the same newest-applied-first list E-D2 uses.
        List<JobApplication> recent =
                jobApplicationService.listForEmployer(employerId, null, "ALL", "0").applications().getContent();
        model.addAttribute("recentApplications",
                recent.size() > RECENT_APPLICATIONS_LIMIT ? recent.subList(0, RECENT_APPLICATIONS_LIMIT) : recent);

        return "employer/dashboard";
    }

    // Finds the KPI by its label rather than a fixed list index, so this never silently
    // breaks if EmployerStatisticsService ever reorders its own KPI row.
    private String awaitingReviewCount(EmployerStatistics stats) {
        for (KpiValue kpi : stats.kpis()) {
            if ("Awaiting review".equals(kpi.label())) {
                return kpi.value();
            }
        }
        return "0";
    }
}
