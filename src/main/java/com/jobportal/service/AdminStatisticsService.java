package com.jobportal.service;

import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.dto.AdminStatistics;
import com.jobportal.dto.ChartData;
import com.jobportal.dto.EngagementMetric;
import com.jobportal.dto.HeadlineCounts;
import com.jobportal.dto.KpiValue;
import com.jobportal.dto.SkillDemandRow;
import com.jobportal.dto.TopJobRow;
import com.jobportal.repository.ActivityLogRepository;
import com.jobportal.repository.AdminReportingJdbcRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.repository.projection.ApplicationStatusCount;
import com.jobportal.repository.projection.CategoryCount;
import com.jobportal.repository.projection.JobStatusCount;
import com.jobportal.repository.projection.TopJobCount;
import com.jobportal.util.DateBuckets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Everything on /admin/statistics.html (Section 6.2 A-D4, 7.6): every KPI, chart and
// table for a 7/30/90 day range, built from repository aggregate queries and grouped in
// Java with DateBuckets (H2 and MySQL do not agree on date functions - Section 7.6).
// Read-only: viewing statistics never changes data, so this service never calls
// ActivityLogService (11.3 contract item 5 only requires logging for state changes).
//
// TWO OF THIS PAGE'S REPORTS ARE SQL, NOT JPA (Section 10.10)
// Everything that groups by time still works the way the rest of this file does. The two
// exceptions both come from AdminReportingJdbcRepository, and neither is a second route
// to the database - it is the same pooled DataSource and the same transaction as
// Hibernate, which that class's header explains at length:
//   * every scalar count on the page (the KPI row, the engagement table's numerators and
//     denominators) now arrives as ONE HeadlineCounts in one round-trip, replacing nine
//     separate `select count(*)` repository calls;
//   * "Most in-demand skills" is a GROUP BY over skills x job_skills x jobs that JPA has
//     no good spelling for.
//
// WHAT THAT COST, AND WHY IT IS ACCEPTABLE (AC-A-D4-1)
// The three range KPIs - New users, Jobs posted, Applications - used to be the .size() of
// the very lists the matching charts are bucketed from, so a KPI and its own chart's data
// table could not disagree by construction. They now come from SQL instead, so the
// guarantee changes shape: both sides still ask the identical question (`timestamp >=
// from`, with `from` computed once by rangeStart below and handed to both), but "the same
// number because it is the same list" becomes "the same number because it is the same
// boundary". That is weaker, so it is asserted rather than assumed -
// AdminReportingJdbcRepositoryTest checks each of the three counts against both its chart
// total and the JPA list it used to be computed from. The trade buys eight fewer
// round-trips and, more usefully, one place where the page's counts are defined.
@Service
public class AdminStatisticsService {

    private static final int TOP_JOBS_LIMIT = 5;

    // How many skills the "Most in-demand skills" card shows. Five, like the Top jobs
    // table above it, would hide most of a small portal's vocabulary; the whole ranking
    // would be a chart with one bar per skill the site has ever seen. Ten is the same
    // compromise the rest of the page makes between "a chart you can read" and "a report
    // that is not just the leader".
    //
    // Public, like JobSpecifications' relevance weights and for the same reason: the
    // ranking it cuts off is checked from another package, and a test that hard-codes 10
    // beside a constant that says 10 is a test that passes after somebody changes one of
    // them.
    public static final int TOP_SKILLS_LIMIT = 10;

    private static final long ACTIVE_USERS_WINDOW_DAYS = 7;
    private static final EnumSet<JobStatus> APPROVAL_DECISIONS = EnumSet.of(JobStatus.APPROVED, JobStatus.REJECTED);
    // The "no data yet" placeholder used across every formatted metric on this page
    // (Section 7.6, "0 views gives –"): an en dash, not a hyphen.
    private static final String NO_DATA_PLACEHOLDER = "–";

    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobStatusChangeRepository jobStatusChangeRepository;
    private final ActivityLogRepository activityLogRepository;
    private final AdminReportingJdbcRepository adminReportingJdbcRepository;
    private final Clock clock;

    // ApplicationStatusChangeRepository and MessageRepository used to be injected here
    // for one `count` each (hires in range, messages sent in range). Both of those counts
    // are now two lines of HEADLINE_COUNTS_SQL, so the dependencies are gone rather than
    // left in place unused. The repository methods themselves are deliberately KEPT: they
    // are the independent second implementation AdminReportingJdbcRepositoryTest compares
    // the SQL against, and a differential test needs the other side to still exist.
    public AdminStatisticsService(UserRepository userRepository, JobRepository jobRepository,
            JobApplicationRepository jobApplicationRepository, JobStatusChangeRepository jobStatusChangeRepository,
            ActivityLogRepository activityLogRepository,
            AdminReportingJdbcRepository adminReportingJdbcRepository, Clock clock) {
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.jobStatusChangeRepository = jobStatusChangeRepository;
        this.activityLogRepository = activityLogRepository;
        this.adminReportingJdbcRepository = adminReportingJdbcRepository;
        this.clock = clock;
    }

    // rawDays is the request's ?days= value, unparsed (Section 7.9 binding rule: bound
    // as a String so an invalid value never reaches the type-mismatch 404 handler);
    // DateBuckets.normaliseDays turns it into 7, 30 or 90.
    @Transactional(readOnly = true)
    public AdminStatistics getStatistics(String rawDays) {
        int days = DateBuckets.normaliseDays(rawDays);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = rangeStart(today, days);

        // Every scalar count the page shows, in one statement and one round-trip
        // (Section 10.10). Taken FIRST so that the KPI row and the engagement table are
        // both built from the same single snapshot of counts, rather than from numbers
        // gathered at nine different moments inside the transaction.
        //
        // activeSince is computed here and not in the SQL: "now minus 7 days" is a date
        // function, and date functions are the one thing hand-written SQL in this project
        // is not allowed to contain (H2 and PostgreSQL do not agree on them - 7.6, 10.7).
        // Java owns the clock, as it does for every other boundary on this page.
        HeadlineCounts counts = adminReportingJdbcRepository.headlineCounts(
                from, LocalDateTime.now(clock).minusDays(ACTIVE_USERS_WINDOW_DAYS), today);

        // Fetched once, each list feeding its own chart. These are the only queries on the
        // page that return rows rather than counts, because bucketing by date happens in
        // Java (Section 7.6) and that needs the timestamps themselves. The matching KPI
        // numbers come from `counts` above, over the identical `from` boundary - see the
        // class header for why that equality is now a tested property rather than a
        // structural one (AC-A-D4-1).
        List<LocalDateTime> jobsPostedTimestamps = jobRepository.findCreatedAtSince(from);
        List<LocalDateTime> applicationTimestamps = jobApplicationRepository.findAppliedAtSince(from);
        List<LocalDateTime> newUserTimestamps = userRepository.findCreatedAtSince(from);
        List<LocalDateTime> loginTimestamps = activityLogRepository.findCreatedAtByTypeSince(ActivityType.LOGIN_SUCCESS, from);

        ChartData applicationsOverTime = DateBuckets.count(applicationTimestamps, today, days, "Applications");
        ChartData jobsPostedOverTime = DateBuckets.count(jobsPostedTimestamps, today, days, "Jobs posted");
        ChartData registrationsOverTime = DateBuckets.count(newUserTimestamps, today, days, "New registrations");
        ChartData loginsOverTime = DateBuckets.count(loginTimestamps, today, days, "Logins");
        ChartData jobsByStatus = jobsByStatusChart();
        ChartData applicationsByCategory = applicationsByCategoryChart(from);
        ChartData applicationOutcomes = applicationOutcomesChart(from);
        ChartData skillDemand = skillDemandChart(today);

        List<KpiValue> kpis = List.of(
                new KpiValue("New users", String.valueOf(counts.newUsers()), null),
                new KpiValue("Jobs posted", String.valueOf(counts.jobsPosted()), null),
                new KpiValue("Applications", String.valueOf(counts.applications()), null),
                new KpiValue("Hires", String.valueOf(counts.hires()), null),
                new KpiValue("Active users (7 days)", String.valueOf(counts.activeUsers7Days()), null),
                new KpiValue("Live jobs", String.valueOf(counts.liveJobs()), null));

        List<TopJobRow> topJobs = topJobs(from);
        List<EngagementMetric> engagementMetrics = engagementMetrics(from, counts);

        return new AdminStatistics(days, kpis, applicationsOverTime, jobsPostedOverTime, registrationsOverTime,
                jobsByStatus, applicationsByCategory, applicationOutcomes, loginsOverTime, skillDemand, topJobs,
                engagementMetrics);
    }

    // The "Most in-demand skills" ranking (Section 10.10), as a ChartData so the page can
    // render it with the same fragments/chart-card every other chart uses - a canvas plus
    // the identical numbers in a collapsible table, which is this project's accessibility
    // rule for charts (Section 7.1) and not something this report gets to opt out of.
    //
    // Unlike jobsByStatusChart and applicationOutcomesChart there is nothing to zero-fill:
    // the labels are not a closed enum but whatever skills employers have actually typed,
    // so a skill no Live job asks for has no row to show and is correctly absent rather
    // than present with a 0. Same shape as applicationsByCategoryChart, for the same
    // reason, and the SQL has already put them in order.
    //
    // The series is labelled "Live jobs" because that is literally what the numbers count,
    // and because the chart-card data table uses it as its value column heading - "Jobs"
    // would quietly invite a reader to compare it with the Jobs-by-status chart's totals,
    // which are all-time and include jobs nobody can apply to.
    private ChartData skillDemandChart(LocalDate today) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (SkillDemandRow row : adminReportingJdbcRepository.topSkillsByLiveJobDemand(today, TOP_SKILLS_LIMIT)) {
            labels.add(row.label());
            values.add(row.liveJobs());
        }
        return new ChartData("Live jobs", labels, values);
    }

    // Jobs by status, all time (Section 6.2 screen item 4): always all four JobStatus
    // values in their declared (and displayed) order, zero-filled, not just the statuses
    // that happen to have at least one job.
    private ChartData jobsByStatusChart() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatusCount row : jobRepository.countGroupedByStatus()) {
            counts.put(row.getStatus(), row.getTotal());
        }
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (JobStatus status : JobStatus.values()) {
            labels.add(status.getLabel());
            values.add(counts.getOrDefault(status, 0L));
        }
        return new ChartData("Jobs", labels, values);
    }

    // Applications by category, in range (Section 6.2 screen item 5): the repository
    // query already orders by count descending, so only categories with at least one
    // application in range appear, in that same order - no zero-fill needed here.
    private ChartData applicationsByCategoryChart(LocalDateTime from) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (CategoryCount row : jobApplicationRepository.countByCategorySince(from)) {
            labels.add(row.getCategory().getLabel());
            values.add(row.getTotal());
        }
        return new ChartData("Applications", labels, values);
    }

    // Application outcomes, in range (Section 6.2 screen item 6): current status of
    // applications submitted in range, in pipeline order (ApplicationStatus's own
    // declared order), zero-filled.
    private ChartData applicationOutcomesChart(LocalDateTime from) {
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatusCount row : jobApplicationRepository.countByStatusSince(from)) {
            counts.put(row.getStatus(), row.getTotal());
        }
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            labels.add(status.getLabel());
            values.add(counts.getOrDefault(status, 0L));
        }
        return new ChartData("Applications", labels, values);
    }

    // Top 5 most-applied jobs in range (Section 6.2 screen item, table): "Hired" is that
    // job's all-time hire count (Section 6.2 A-D4 table has no date qualifier on this
    // column, unlike "Applications"), one small lookup per row since at most 5 rows exist.
    private List<TopJobRow> topJobs(LocalDateTime from) {
        List<TopJobRow> rows = new ArrayList<>();
        for (TopJobCount row : jobApplicationRepository.findTopJobsSince(from, PageRequest.of(0, TOP_JOBS_LIMIT))) {
            long hired = jobApplicationRepository.countByJob_IdAndStatus(row.getJobId(), ApplicationStatus.HIRED);
            rows.add(new TopJobRow(row.getJobId(), row.getTitle(), row.getCompany(), row.getTotal(), hired));
        }
        return rows;
    }

    // The admin engagement table (Section 7.6): six metrics, each with its formula
    // spelled out so the table doubles as documentation, matching Section 7.6's own
    // wording as closely as a display string allows.
    //
    // Six of the eight numbers this table needs arrive in `counts`, already fetched. This
    // method used to open with six more `count` queries, which is the shape of reporting
    // code that quietly costs a round-trip per row of a table - and the reason the whole
    // page's counts moved into one statement (Section 10.10). Only the approval
    // turnaround still queries, because it is not a count: it needs the status-change
    // rows themselves to pair each decision with its submission.
    private List<EngagementMetric> engagementMetrics(LocalDateTime from, HeadlineCounts counts) {
        List<EngagementMetric> metrics = new ArrayList<>();
        metrics.add(new EngagementMetric("Active users (7 days)",
                "Users with lastLoginAt in the last 7 days ÷ all users",
                ratio(counts.activeUsers7Days(), counts.totalUsers())));
        metrics.add(new EngagementMetric("Seeker participation",
                "Distinct seekers with an application in range ÷ enabled seekers",
                ratio(counts.applyingSeekers(), counts.enabledSeekers())));
        metrics.add(new EngagementMetric("Applications per applying seeker",
                "Applications in range ÷ distinct seekers who applied in range",
                perApplyingSeeker(counts.applications(), counts.applyingSeekers())));
        metrics.add(new EngagementMetric("Employer posting rate",
                "Distinct employers who submitted a job in range ÷ all employers",
                ratio(counts.postingEmployers(), counts.allEmployers())));
        metrics.add(new EngagementMetric("Messages sent", "Messages with sentAt in range",
                String.valueOf(counts.messagesSent())));
        metrics.add(new EngagementMetric("Approval turnaround",
                "Mean hours from a job entering Pending approval to its next approve or reject decision",
                approvalTurnaround(from)));
        return metrics;
    }

    // Mean hours from a job entering PENDING_APPROVAL to its next approve/reject
    // decision, for every decision made in range (Section 7.6). Two-step query, exactly
    // as Section 7.6 specifies: step 1 finds the decisions in range; step 2 loads every
    // status row of those jobs with no date filter, so the preceding PENDING_APPROVAL
    // row is found even when it is older than the window, then the two are paired here.
    private String approvalTurnaround(LocalDateTime from) {
        List<JobStatusChange> decisions =
                jobStatusChangeRepository.findByToStatusInAndChangedAtGreaterThanEqual(APPROVAL_DECISIONS, from);
        if (decisions.isEmpty()) {
            return NO_DATA_PLACEHOLDER;
        }
        List<Long> decidedJobIds = decisions.stream().map(change -> change.getJob().getId()).distinct().toList();
        Map<Long, List<JobStatusChange>> rowsByJob = new HashMap<>();
        for (JobStatusChange row : jobStatusChangeRepository.findByJob_IdInOrderByJob_IdAscChangedAtAsc(decidedJobIds)) {
            rowsByJob.computeIfAbsent(row.getJob().getId(), id -> new ArrayList<>()).add(row);
        }

        List<Double> turnaroundHours = new ArrayList<>();
        for (JobStatusChange decision : decisions) {
            JobStatusChange latestPending = latestPendingBefore(rowsByJob.getOrDefault(decision.getJob().getId(), List.of()), decision);
            if (latestPending != null) {
                turnaroundHours.add(Duration.between(latestPending.getChangedAt(), decision.getChangedAt()).toMinutes() / 60.0);
            }
        }
        if (turnaroundHours.isEmpty()) {
            return NO_DATA_PLACEHOLDER;
        }
        double meanHours = turnaroundHours.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return String.format(Locale.ROOT, "%.1f hours", meanHours);
    }

    // The latest PENDING_APPROVAL row of the same job that is not after the decision -
    // "next approve or reject decision" pairs with the PENDING_APPROVAL row immediately
    // before it, not the job's very first submission.
    private JobStatusChange latestPendingBefore(List<JobStatusChange> jobRows, JobStatusChange decision) {
        JobStatusChange latestPending = null;
        for (JobStatusChange row : jobRows) {
            if (row.getToStatus() == JobStatus.PENDING_APPROVAL && !row.getChangedAt().isAfter(decision.getChangedAt())
                    && (latestPending == null || row.getChangedAt().isAfter(latestPending.getChangedAt()))) {
                latestPending = row;
            }
        }
        return latestPending;
    }

    // "N of M (P%)" (Section 7.6 seed values: "6 of 10 (60%)", "4 of 6 (67%)"),
    // half-up rounded to the nearest whole percent.
    private String ratio(long numerator, long denominator) {
        long percent = denominator <= 0 ? 0 : Math.round(100.0 * numerator / denominator);
        return numerator + " of " + denominator + " (" + percent + "%)";
    }

    // "A ÷ B = C" (Section 7.6 seed value "13 ÷ 4 = 3.3"), one decimal place.
    private String perApplyingSeeker(long applicationsInRange, long applyingSeekers) {
        if (applyingSeekers <= 0) {
            return NO_DATA_PLACEHOLDER;
        }
        double perSeeker = (double) applicationsInRange / applyingSeekers;
        return applicationsInRange + " ÷ " + applyingSeekers + " = " + String.format(Locale.ROOT, "%.1f", perSeeker);
    }

    // Mirrors DateBuckets' own bucket-start rule (7.6): the first bucket starts at
    // today - (days - 1) for a 7/30 day view, or today - 90 for the 13 weekly buckets of
    // a 90 day view. Every "in range" repository query here uses this same boundary, so
    // a KPI count always adds up to its own chart's data table (AC-A-D4-1). DateBuckets
    // keeps the equivalent logic private, so it is repeated here rather than exposed
    // just for this one caller - util/** is not this service's file to change.
    private LocalDateTime rangeStart(LocalDate today, int days) {
        LocalDate firstDay = days == 90 ? today.minusDays(90) : today.minusDays(days - 1L);
        return firstDay.atStartOfDay();
    }
}
