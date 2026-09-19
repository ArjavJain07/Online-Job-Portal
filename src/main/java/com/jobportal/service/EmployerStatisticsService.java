package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.ChartData;
import com.jobportal.dto.EmployerStatistics;
import com.jobportal.dto.EngagementMetric;
import com.jobportal.dto.JobStatsRow;
import com.jobportal.dto.KpiValue;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.JobViewRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.projection.ApplicationStatusCount;
import com.jobportal.repository.projection.FirstResponseRow;
import com.jobportal.repository.projection.JobStatusPairCount;
import com.jobportal.repository.projection.MessageEventRow;
import com.jobportal.util.DateBuckets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Everything on /employer/statistics.html (Section 6.3 E-D5, 7.6), scoped to one
// employer and, when the request narrows it, to one of their own jobs. Read-only, so it
// never calls ActivityLogService (11.3 contract item 5 only requires logging for state
// changes).
@Service
public class EmployerStatisticsService {

    private static final EnumSet<ApplicationStatus> IN_PROGRESS_STATUSES =
            EnumSet.of(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEW);
    private static final EnumSet<ApplicationStatus> SHORTLISTED_OR_FURTHER_STATUSES =
            EnumSet.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEW, ApplicationStatus.HIRED);
    // The "no data yet" placeholder used across every formatted metric on this page
    // (Section 7.6, "0 views gives –"): an en dash, not a hyphen.
    private static final String NO_DATA_PLACEHOLDER = "–";

    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobViewRepository jobViewRepository;
    private final ApplicationStatusChangeRepository applicationStatusChangeRepository;
    private final MessageRepository messageRepository;
    private final Clock clock;

    public EmployerStatisticsService(JobRepository jobRepository, JobApplicationRepository jobApplicationRepository,
            JobViewRepository jobViewRepository, ApplicationStatusChangeRepository applicationStatusChangeRepository,
            MessageRepository messageRepository, Clock clock) {
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.jobViewRepository = jobViewRepository;
        this.applicationStatusChangeRepository = applicationStatusChangeRepository;
        this.messageRepository = messageRepository;
        this.clock = clock;
    }

    // rawDays and rawJobId are the request's raw ?days=/?jobId= values (Section 7.9
    // binding rule: both bound as String); a foreign, unknown or non-numeric jobId is
    // ignored, falling back to every one of this employer's own jobs (Section 6.3 E-D5).
    @Transactional(readOnly = true)
    public EmployerStatistics getStatistics(Long employerId, String rawDays, String rawJobId) {
        int days = DateBuckets.normaliseDays(rawDays);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime from = rangeStart(today, days);
        Long jobId = resolveJobId(employerId, rawJobId);

        List<LocalDateTime> applicationTimestamps = jobApplicationRepository.findAppliedAtSince(employerId, jobId, from);
        ChartData applicationsOverTime = DateBuckets.count(applicationTimestamps, today, days, "Applications");

        Map<ApplicationStatus, Long> statusCountsInRange =
                statusCounts(jobApplicationRepository.countByStatusSince(employerId, jobId, from));
        ChartData hiringPipeline = pipelineChart(statusCountsInRange);

        List<JobStatsRow> jobStats = jobStats(employerId, jobId, today);
        ChartData applicationsPerJob = applicationsPerJobChart(jobStats);

        List<MessageEventRow> messageEvents = messageRepository.findMessageEvents(employerId, jobId);
        List<ChartData> messagesOverTime = messagesChart(messageEvents, employerId, today, days);

        long total = sum(statusCountsInRange, EnumSet.allOf(ApplicationStatus.class));
        long awaitingReview = statusCountsInRange.getOrDefault(ApplicationStatus.APPLIED, 0L);
        long inProgress = sum(statusCountsInRange, IN_PROGRESS_STATUSES);
        long hired = statusCountsInRange.getOrDefault(ApplicationStatus.HIRED, 0L);
        long rejected = statusCountsInRange.getOrDefault(ApplicationStatus.REJECTED, 0L);
        long withdrawn = statusCountsInRange.getOrDefault(ApplicationStatus.WITHDRAWN, 0L);

        // Dated view analytics (JobView, not Job.viewCount - see JobView's class comment):
        // views over time and the view-to-application funnel, both scoped to the same
        // range/job as applicationsOverTime.
        List<LocalDateTime> viewTimestamps = jobViewRepository.findViewedAtSince(employerId, jobId, from);
        ChartData viewsOverTime = DateBuckets.count(viewTimestamps, today, days, "Views");
        ChartData viewToApplicationFunnel = viewToApplicationFunnelChart(viewsOverTime, total);

        List<KpiValue> kpis = List.of(
                new KpiValue("Total applications", String.valueOf(total), null),
                new KpiValue("Awaiting review", String.valueOf(awaitingReview), null),
                new KpiValue("In progress", String.valueOf(inProgress), null),
                new KpiValue("Hired", String.valueOf(hired), null),
                new KpiValue("Rejected", String.valueOf(rejected), null),
                new KpiValue("Withdrawn", String.valueOf(withdrawn), null));

        List<EngagementMetric> engagementMetrics =
                engagementMetrics(employerId, from, jobStats, messageEvents, total, withdrawn);

        return new EmployerStatistics(days, jobId, kpis, applicationsOverTime, hiringPipeline, applicationsPerJob,
                messagesOverTime, viewsOverTime, viewToApplicationFunnel, jobStats, engagementMetrics);
    }

    // Apply rate (Section 6.3 E-D5): applications / views as a percentage, one decimal
    // place, or the en dash placeholder when there are no views to divide by. Public and
    // pure (no field access) so RecommendationScorerTest's sibling, EmployerStatisticsTest
    // #applyRateComputed, can call it directly - the plan's own worked example is
    // applyRate(views 20, applications 5) = 25.0%.
    public String applyRate(long views, long applications) {
        return percentage(applications, views);
    }

    private Long resolveJobId(Long employerId, String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(rawJobId.trim());
            return jobRepository.findByIdAndEmployer_Id(id, employerId).map(Job::getId).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<ApplicationStatus, Long> statusCounts(List<ApplicationStatusCount> rows) {
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatusCount row : rows) {
            counts.put(row.getStatus(), row.getTotal());
        }
        return counts;
    }

    // Hiring pipeline (Section 6.3 E-D5 chart 2): applications by current status
    // submitted in range, in pipeline order (ApplicationStatus's own declared order),
    // zero-filled.
    private ChartData pipelineChart(Map<ApplicationStatus, Long> statusCountsInRange) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            labels.add(status.getLabel());
            values.add(statusCountsInRange.getOrDefault(status, 0L));
        }
        return new ChartData("Applications", labels, values);
    }

    // The per-job table (Section 6.3 E-D5, all time, because views are not dated): every
    // one of the employer's own jobs, or just the narrowed one, with its all-time
    // application pivot (Section 7.6's per-job pivot query, employer-scoped already).
    private List<JobStatsRow> jobStats(Long employerId, Long jobId, LocalDate today) {
        List<Job> jobs = jobId != null
                ? jobRepository.findByIdAndEmployer_Id(jobId, employerId).map(List::of).orElse(List.of())
                : jobRepository.findAll(JobSpecifications.hasEmployer(employerId), Sort.by(Sort.Order.asc("title")));

        Map<Long, Map<ApplicationStatus, Long>> pivot = new HashMap<>();
        for (JobStatusPairCount row : jobApplicationRepository.countByJobAndStatus(employerId)) {
            pivot.computeIfAbsent(row.getJobId(), id -> new EnumMap<>(ApplicationStatus.class)).put(row.getStatus(), row.getTotal());
        }

        List<JobStatsRow> rows = new ArrayList<>();
        for (Job job : jobs) {
            Map<ApplicationStatus, Long> counts = pivot.getOrDefault(job.getId(), Map.of());
            long applications = sum(counts, EnumSet.allOf(ApplicationStatus.class));
            long shortlistedOrFurther = sum(counts, SHORTLISTED_OR_FURTHER_STATUSES);
            long hired = counts.getOrDefault(ApplicationStatus.HIRED, 0L);
            String status = job.displayStatus(today).getLabel();
            rows.add(new JobStatsRow(job.getId(), job.getTitle(), status, job.getViewCount(), applications,
                    applyRate(job.getViewCount(), applications), shortlistedOrFurther, hired));
        }
        return rows;
    }

    private long sum(Map<ApplicationStatus, Long> counts, Set<ApplicationStatus> statuses) {
        long total = 0;
        for (ApplicationStatus status : statuses) {
            total += counts.getOrDefault(status, 0L);
        }
        return total;
    }

    // Applications per job (Section 6.3 E-D5 chart 3): reuses the same all-time per-job
    // figures as the table above, so the chart and the table can never disagree.
    private ChartData applicationsPerJobChart(List<JobStatsRow> jobStats) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (JobStatsRow row : jobStats) {
            labels.add(row.title());
            values.add(row.applications());
        }
        return new ChartData("Applications", labels, values);
    }

    // View-to-application funnel (dated view analytics feature, Section 6.3 E-D5): two
    // stages, both scoped to the selected range/job so the funnel tells the same "how is
    // this period doing" story as applicationsOverTime and the rest of this page - unlike
    // the per-job table's all-time Views and Apply rate columns below, which are left
    // exactly as they were (Section 7.6: "views are not dated" there, deliberately, because
    // Job.viewCount has no date to give them). Reuses viewsOverTime's own bucket totals
    // instead of a second query, the same reasoning applicationsPerJobChart gives for
    // reusing jobStats: the chart and the number it is built from can never disagree.
    private ChartData viewToApplicationFunnelChart(ChartData viewsOverTime, long applicationsInRange) {
        long viewsInRange = viewsOverTime.values().stream().mapToLong(Long::longValue).sum();
        return new ChartData("Count", List.of("Views", "Applications"), List.of(viewsInRange, applicationsInRange));
    }

    // Candidate engagement: messages over time (Section 6.3 E-D5 chart 4, 7.6): every
    // event is bucketed regardless of date, since DateBuckets silently drops anything
    // outside the displayed range - the same technique the KPI-feeding charts use.
    private List<ChartData> messagesChart(List<MessageEventRow> events, Long employerId, LocalDate today, int days) {
        List<LocalDateTime> employerMessageTimes = new ArrayList<>();
        List<LocalDateTime> candidateReplyTimes = new ArrayList<>();
        for (MessageEventRow event : events) {
            if (event.getSenderId().equals(employerId)) {
                employerMessageTimes.add(event.getSentAt());
            } else {
                candidateReplyTimes.add(event.getSentAt());
            }
        }
        return List.of(
                DateBuckets.count(employerMessageTimes, today, days, "Employer messages"),
                DateBuckets.count(candidateReplyTimes, today, days, "Candidate replies"));
    }

    // The candidate engagement table (Section 6.3 E-D5): five metrics, each with its
    // definition spelled out so the table doubles as documentation.
    private List<EngagementMetric> engagementMetrics(Long employerId, LocalDateTime from, List<JobStatsRow> jobStats,
            List<MessageEventRow> messageEvents, long totalApplicationsInRange, long withdrawnInRange) {
        long totalViews = jobStats.stream().mapToLong(JobStatsRow::views).sum();
        long totalApplicationsAllTime = jobStats.stream().mapToLong(JobStatsRow::applications).sum();

        // Group every message event into its thread (one per application), preserving
        // the query's own sentAt order, then find each thread's first employer message
        // and whether the candidate replied to it afterwards.
        Map<Long, List<MessageEventRow>> threads = new LinkedHashMap<>();
        Set<Long> candidatesMessagedInRange = new HashSet<>();
        for (MessageEventRow event : messageEvents) {
            threads.computeIfAbsent(event.getApplicationId(), id -> new ArrayList<>()).add(event);
            if (event.getSenderId().equals(employerId) && !event.getSentAt().isBefore(from)) {
                candidatesMessagedInRange.add(event.getRecipientId());
            }
        }
        long qualifyingThreads = 0;
        long repliedThreads = 0;
        for (List<MessageEventRow> thread : threads.values()) {
            MessageEventRow firstEmployerMessage = thread.stream()
                    .filter(event -> event.getSenderId().equals(employerId))
                    .findFirst().orElse(null);
            if (firstEmployerMessage == null || firstEmployerMessage.getSentAt().isBefore(from)) {
                continue;
            }
            qualifyingThreads++;
            boolean replied = thread.stream().anyMatch(event -> !event.getSenderId().equals(employerId)
                    && event.getSentAt().isAfter(firstEmployerMessage.getSentAt()));
            if (replied) {
                repliedThreads++;
            }
        }

        List<FirstResponseRow> firstResponses = applicationStatusChangeRepository.findFirstResponses(employerId,
                Role.EMPLOYER, from);

        List<EngagementMetric> metrics = new ArrayList<>();
        metrics.add(new EngagementMetric("Apply rate",
                "Applications ÷ views, across the employer's jobs",
                applyRate(totalViews, totalApplicationsAllTime)));
        metrics.add(new EngagementMetric("Candidate reply rate",
                "Threads where the employer's first message was sent in range and the candidate replied "
                        + "afterwards ÷ threads whose first employer message was sent in range",
                conversationRatio(repliedThreads, qualifyingThreads)));
        metrics.add(new EngagementMetric("Withdrawal rate",
                "Withdrawn ÷ all applications submitted in range",
                percentage(withdrawnInRange, totalApplicationsInRange)));
        metrics.add(new EngagementMetric("Candidates messaged",
                "Distinct candidates who received an employer message in range",
                String.valueOf(candidatesMessagedInRange.size())));
        metrics.add(new EngagementMetric("Average first response",
                "Mean days from appliedAt to the first status change made by the employer, for applications "
                        + "submitted in range that have one",
                averageFirstResponseDays(firstResponses)));
        return metrics;
    }

    private String averageFirstResponseDays(List<FirstResponseRow> rows) {
        if (rows.isEmpty()) {
            return NO_DATA_PLACEHOLDER;
        }
        double totalDays = 0.0;
        for (FirstResponseRow row : rows) {
            totalDays += Duration.between(row.getAppliedAt(), row.getFirstChange()).toMinutes() / (60.0 * 24.0);
        }
        return String.format(Locale.ROOT, "%.1f days", totalDays / rows.size());
    }

    // A plain percentage, one decimal place (Section 7.6 seed values "10.0%", "12.5%"),
    // or the en dash placeholder when the denominator is 0.
    private String percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return NO_DATA_PLACEHOLDER;
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * numerator / denominator);
    }

    // "N of M conversations (P%)" (Section 6.3 E-D5 seed value "2 of 3 conversations
    // (67%)"), half-up rounded to the nearest whole percent.
    private String conversationRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return NO_DATA_PLACEHOLDER;
        }
        long percent = Math.round(100.0 * numerator / denominator);
        return numerator + " of " + denominator + " conversations (" + percent + "%)";
    }

    // Mirrors DateBuckets' own bucket-start rule (7.6) - see AdminStatisticsService's
    // identical helper for why this is duplicated rather than shared.
    private LocalDateTime rangeStart(LocalDate today, int days) {
        LocalDate firstDay = days == 90 ? today.minusDays(90) : today.minusDays(days - 1L);
        return firstDay.atStartOfDay();
    }
}
