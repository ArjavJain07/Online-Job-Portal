package com.jobportal.web.employer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobView;
import com.jobportal.dto.ChartData;
import com.jobportal.dto.EmployerStatistics;
import com.jobportal.dto.EngagementMetric;
import com.jobportal.dto.JobStatsRow;
import com.jobportal.dto.KpiValue;
import com.jobportal.repository.JobViewRepository;
import com.jobportal.service.EmployerStatisticsService;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

// Employer application/candidate-engagement statistics (Section 6.3 E-D5, 7.6), always
// scoped to the signed-in employer's own jobs, checked against the real Section 13 seed
// data. EmployerStatisticsService is called directly (its own record is the single source
// of truth the template only renders), so an assertion here checks the same number the
// screen shows without depending on HTML layout.
class EmployerStatisticsTest extends IntegrationTestBase {

    @Autowired
    private EmployerStatisticsService employerStatisticsService;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private JobViewRepository jobViewRepository;
    @Autowired
    private Clock clock;

    // AC-E-D5-1: Acme's 30-day KPIs (Total 8, Awaiting review 1, In progress 3, Hired 1,
    // Rejected 2, Withdrawn 1) never include Globex's applications; Globex totals 5 for 30
    // days and 7 for 90 days, its own separate scope.
    @Test
    void kpisScopedToOwnJobs() throws Exception {
        Long acmeId = data.userId("hr@acme.local");
        Long globexId = data.userId("talent@globex.local");

        EmployerStatistics acmeStats = employerStatisticsService.getStatistics(acmeId, "30", null);
        assertThat(kpi(acmeStats, "Total applications")).isEqualTo("8");
        assertThat(kpi(acmeStats, "Awaiting review")).isEqualTo("1");
        assertThat(kpi(acmeStats, "In progress")).isEqualTo("3");
        assertThat(kpi(acmeStats, "Hired")).isEqualTo("1");
        assertThat(kpi(acmeStats, "Rejected")).isEqualTo("2");
        assertThat(kpi(acmeStats, "Withdrawn")).isEqualTo("1");

        assertThat(kpi(employerStatisticsService.getStatistics(globexId, "30", null), "Total applications"))
                .isEqualTo("5");
        assertThat(kpi(employerStatisticsService.getStatistics(globexId, "90", null), "Total applications"))
                .isEqualTo("7");

        UserDetails acme = userDetailsService.loadUserByUsername("hr@acme.local");
        mockMvc.perform(get("/employer/statistics").with(user(acme)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Statistics")));
    }

    // AC-E-D5-2: Acme's Java Developer row shows Views 40, Applications 4, Apply rate
    // 10.0%; the unit example from Section 7.6 is applyRate(views 20, applications 5) =
    // 25.0%, and 0 views gives the en-dash placeholder.
    @Test
    void applyRateComputed() {
        Long acmeId = data.userId("hr@acme.local");
        EmployerStatistics stats = employerStatisticsService.getStatistics(acmeId, "30", null);

        JobStatsRow javaDeveloperRow = jobStats(stats, "Java Developer");
        assertThat(javaDeveloperRow.views()).isEqualTo(40);
        assertThat(javaDeveloperRow.applications()).isEqualTo(4);
        assertThat(javaDeveloperRow.applyRate()).isEqualTo("10.0%");

        assertThat(employerStatisticsService.applyRate(20, 5)).isEqualTo("25.0%");
        assertThat(employerStatisticsService.applyRate(0, 3)).isEqualTo("–");
    }

    // AC-E-D5-3 (first clause): a jobId naming one of Globex's jobs is ignored for Acme,
    // falling back to every one of Acme's own jobs (stats.jobId() null, Total 8).
    @Test
    void foreignJobIdIgnored() {
        Long acmeId = data.userId("hr@acme.local");
        Long globexJobId = data.jobId("Data Analyst");

        EmployerStatistics stats = employerStatisticsService.getStatistics(acmeId, "30", globexJobId.toString());

        assertThat(stats.jobId()).isNull();
        assertThat(kpi(stats, "Total applications")).isEqualTo("8");
    }

    // AC-E-D5-3 (remaining clauses): Acme's candidate reply rate, candidates messaged,
    // withdrawal rate and average first response, all for the default 30-day range.
    @Test
    void engagementMetricsMatchSeedData() {
        Long acmeId = data.userId("hr@acme.local");
        EmployerStatistics stats = employerStatisticsService.getStatistics(acmeId, "30", null);

        assertThat(metric(stats, "Candidate reply rate")).isEqualTo("2 of 3 conversations (67%)");
        assertThat(metric(stats, "Candidates messaged")).isEqualTo("3");
        assertThat(metric(stats, "Withdrawal rate")).isEqualTo("12.5%");
        assertThat(metric(stats, "Average first response")).isEqualTo("2.8 days");
    }

    // AC-E-D5-4: Acme's messages-over-time chart for days=30 totals 4 employer messages
    // (MSG1, MSG3, MSG4, MSG7) and 2 candidate replies (MSG2, MSG8); Globex totals 1 and 1
    // (MSG5, MSG6); narrowing Acme to Frontend Developer (J3, thread A6) shows 1 and 1.
    @Test
    void messagesChartSplitsEmployerAndCandidate() {
        Long acmeId = data.userId("hr@acme.local");
        Long globexId = data.userId("talent@globex.local");

        EmployerStatistics acmeStats = employerStatisticsService.getStatistics(acmeId, "30", null);
        assertThat(acmeStats.messagesOverTime()).hasSize(2);
        assertThat(acmeStats.messagesOverTime().get(0).label()).isEqualTo("Employer messages");
        assertThat(sum(acmeStats.messagesOverTime().get(0))).isEqualTo(4);
        assertThat(acmeStats.messagesOverTime().get(1).label()).isEqualTo("Candidate replies");
        assertThat(sum(acmeStats.messagesOverTime().get(1))).isEqualTo(2);

        EmployerStatistics globexStats = employerStatisticsService.getStatistics(globexId, "30", null);
        assertThat(sum(globexStats.messagesOverTime().get(0))).isEqualTo(1);
        assertThat(sum(globexStats.messagesOverTime().get(1))).isEqualTo(1);

        Long frontendDeveloperId = data.jobId("Frontend Developer");
        EmployerStatistics scoped = employerStatisticsService.getStatistics(acmeId, "30", frontendDeveloperId.toString());
        assertThat(sum(scoped.messagesOverTime().get(0))).isEqualTo(1);
        assertThat(sum(scoped.messagesOverTime().get(1))).isEqualTo(1);
    }

    // Dated view analytics (JobView, not Job.viewCount - see JobView's class comment):
    // viewsOverTime buckets JobView rows the same way applicationsOverTime buckets
    // JobApplication rows, and the funnel pairs the range's view total with the range's
    // application total (the same "Total applications" KPI kpisScopedToOwnJobs already
    // proves). DataSeeder does not backfill historical JobView rows (Section 13 seed data
    // predates this feature), so this test creates its own.
    @Test
    void viewsOverTimeAndFunnelScopedToRange() {
        Long acmeId = data.userId("hr@acme.local");
        Job javaJob = data.job("Java Developer");
        LocalDateTime now = LocalDateTime.now(clock);

        saveView(javaJob, now.minusDays(2));
        saveView(javaJob, now.minusDays(2));
        saveView(javaJob, now.minusDays(40)); // outside the default 30-day window

        EmployerStatistics stats = employerStatisticsService.getStatistics(acmeId, "30", null);

        assertThat(stats.viewsOverTime().label()).isEqualTo("Views");
        assertThat(sum(stats.viewsOverTime())).isEqualTo(2);

        assertThat(stats.viewToApplicationFunnel().labels()).containsExactly("Views", "Applications");
        assertThat(stats.viewToApplicationFunnel().values().get(0)).isEqualTo(2L);
        assertThat(stats.viewToApplicationFunnel().values().get(1))
                .isEqualTo(Long.parseLong(kpi(stats, "Total applications")));
    }

    // jobId narrows viewsOverTime exactly like it narrows applicationsOverTime
    // (foreignJobIdIgnored above covers the "unknown employer's job" case; this covers a
    // real own-job narrowing).
    @Test
    void viewsOverTimeNarrowsByJobId() {
        Long acmeId = data.userId("hr@acme.local");
        Job javaJob = data.job("Java Developer");
        Job frontendJob = data.job("Frontend Developer");
        LocalDateTime now = LocalDateTime.now(clock);

        saveView(javaJob, now.minusDays(1));
        saveView(frontendJob, now.minusDays(1));

        EmployerStatistics scoped = employerStatisticsService.getStatistics(acmeId, "30", javaJob.getId().toString());

        assertThat(sum(scoped.viewsOverTime())).isEqualTo(1);
    }

    private void saveView(Job job, LocalDateTime viewedAt) {
        JobView view = new JobView();
        view.setJob(job);
        view.setViewedAt(viewedAt);
        jobViewRepository.save(view);
    }

    private long sum(ChartData chart) {
        return chart.values().stream().mapToLong(Long::longValue).sum();
    }

    private String kpi(EmployerStatistics stats, String label) {
        for (KpiValue kpiValue : stats.kpis()) {
            if (kpiValue.label().equals(label)) {
                return kpiValue.value();
            }
        }
        throw new IllegalStateException("No KPI named " + label);
    }

    private String metric(EmployerStatistics stats, String name) {
        for (EngagementMetric metric : stats.engagementMetrics()) {
            if (metric.name().equals(name)) {
                return metric.value();
            }
        }
        throw new IllegalStateException("No engagement metric named " + name);
    }

    private JobStatsRow jobStats(EmployerStatistics stats, String title) {
        for (JobStatsRow row : stats.jobStats()) {
            if (row.title().equals(title)) {
                return row;
            }
        }
        throw new IllegalStateException("No job stats row for " + title);
    }
}
