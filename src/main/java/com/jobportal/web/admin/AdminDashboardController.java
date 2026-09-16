package com.jobportal.web.admin;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.ActivityDto;
import com.jobportal.dto.ChartData;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.ActivityLogService;
import com.jobportal.service.SettingsService;
import com.jobportal.util.DateBuckets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// The real admin dashboard (Section 6.2 DASH-A, 11.2 M3): KPI cards, the pending-approvals
// mini table, the live activity widget and the latest-applications panel. Everything here
// is a read-only query, so - per this slice's file ownership note - the queries live
// directly in the controller instead of a new AdminDashboardService: the plan does not name
// such a service, and the repositories involved (UserRepository, JobRepository,
// JobApplicationRepository) belong to other agents' slices, so nothing here may add a
// method to them, only call what already exists.
//
// The "Applications, last 30 days" chart (Section 6.2 DASH-A, 7.6) is built here in M7,
// alongside the "Full statistics" link on admin/dashboard.html - Section 11.2 M3 shipped
// everything else on this page first, M7 adds only this one chart card.
@Controller
public class AdminDashboardController {

    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final ActivityLogService activityLogService;
    private final SettingsService settingsService;
    private final Clock clock;

    public AdminDashboardController(UserRepository userRepository, JobRepository jobRepository,
            JobApplicationRepository jobApplicationRepository, ActivityLogService activityLogService,
            SettingsService settingsService, Clock clock) {
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.activityLogService = activityLogService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);

        addKpiCards(model, today, now);
        addPendingApprovals(model);
        addLiveActivity(model);
        addApplicationsChart(model, today);

        model.addAttribute("feedIntervalMs", settingsService.get().getFeedRefreshSeconds() * 1000);
        return "admin/dashboard";
    }

    // AC-DA-1: Total users (with its role breakdown), Live jobs, Pending approvals,
    // Applications today, Active users (7 days).
    private void addKpiCards(Model model, LocalDate today, LocalDateTime now) {
        long adminCount = countByRole(Role.ADMIN);
        long employerCount = countByRole(Role.EMPLOYER);
        long seekerCount = countByRole(Role.JOB_SEEKER);

        model.addAttribute("totalUsers", adminCount + employerCount + seekerCount);
        model.addAttribute("totalUsersBreakdown", roleBreakdown(adminCount, employerCount, seekerCount));
        model.addAttribute("liveJobs", jobRepository.count(JobSpecifications.live(today)));
        model.addAttribute("pendingApprovals", jobRepository.count(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL)));
        model.addAttribute("applicationsToday", jobApplicationRepository.findAppliedAtSince(today.atStartOfDay()).size());
        model.addAttribute("activeUsers7d", userRepository.countByLastLoginAtGreaterThanEqual(now.minusDays(7)));
    }

    // 5 oldest PENDING_APPROVAL jobs, oldest first (a queue) - the same ordering rule as
    // the pending tab of /admin/jobs (Section 6.2 A-F2).
    private void addPendingApprovals(Model model) {
        List<Job> pendingJobs = jobRepository.findAll(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "createdAt"))).getContent();
        model.addAttribute("pendingJobs", pendingJobs);
    }

    // Live activity widget (last 10 events) and the latest-applications panel (last 5
    // APPLICATION_SUBMITTED events), both fed to fragments/activity-feed exactly as on
    // /admin/activity, so the same JS keeps both pages live (Section 7.7).
    private void addLiveActivity(Model model) {
        List<ActivityDto> liveEvents = activityLogService.latest10().stream().map(ActivityDto::from).toList();
        model.addAttribute("liveEvents", liveEvents);
        model.addAttribute("liveEventsLastId", liveEvents.isEmpty() ? 0L : liveEvents.get(0).id());
        model.addAttribute("latestApplications",
                activityLogService.latestApplications().stream().map(ActivityDto::from).toList());
    }

    // "Applications, last 30 days" (Section 6.2 DASH-A chart, Section 7.6 charts-by-page
    // table): the same fixed 30-day window and bucketing DateBuckets.count uses elsewhere,
    // fed by the same repository query AdminStatisticsService uses for its own "Applications
    // over time" chart, so the two never disagree just because they were built separately.
    private void addApplicationsChart(Model model, LocalDate today) {
        LocalDateTime from = today.minusDays(29).atStartOfDay();
        List<LocalDateTime> applicationTimestamps = jobApplicationRepository.findAppliedAtSince(from);
        ChartData applicationsChart = DateBuckets.count(applicationTimestamps, today, 30, "Applications");
        model.addAttribute("applicationsChart", applicationsChart);
    }

    // No countByRole method exists on UserRepository (owned by another slice), so this
    // reuses the admin user list's own search query with a one-row page: Spring Data still
    // runs the count query over the whole matching set, only the fetched page is small.
    private long countByRole(Role role) {
        return userRepository.search(null, role, null, PageRequest.of(0, 1)).getTotalElements();
    }

    // "1 admin - 3 employers - 6 seekers" (Section 6.2 DASH-A), built from live counts
    // rather than hard-coded so it still reads correctly after users are added or removed.
    private String roleBreakdown(long admins, long employers, long seekers) {
        return count(admins, "admin") + " · " + count(employers, "employer") + " · " + count(seekers, "seeker");
    }

    private String count(long value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }
}
