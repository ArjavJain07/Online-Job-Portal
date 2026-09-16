package com.jobportal.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.dto.AdminStatistics;
import com.jobportal.dto.ChartData;
import com.jobportal.dto.EngagementMetric;
import com.jobportal.dto.KpiValue;
import com.jobportal.service.AdminStatisticsService;
import com.jobportal.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.ResultActions;

// Admin job/engagement statistics (Section 6.2 A-D4, 7.6) against the real Section 13 seed
// data. AdminStatisticsService is called directly wherever a chart's own numbers need
// checking (its ChartData/KpiValue records are the single source of truth the template
// only renders, Section 7.6), plus one MockMvc check that the real page renders those same
// numbers so a template regression cannot slip through unnoticed.
class AdminStatisticsTest extends IntegrationTestBase {

    @Autowired
    private AdminStatisticsService adminStatisticsService;
    @Autowired
    private UserDetailsService userDetailsService;

    // AC-A-D4-1: the Applications KPI is 13 for days=30, and the chart backing it has
    // non-zero values on exactly 13 distinct days that add up to 13 - the same "13, on 13
    // distinct days" fact DataSeederTest#applicationsInLast30DaysMatchSection13 proves at
    // the repository level.
    @Test
    void thirtyDayKpisMatchSeedData() throws Exception {
        AdminStatistics stats = adminStatisticsService.getStatistics("30");

        assertThat(kpi(stats, "Applications")).isEqualTo("13");
        List<Long> values = stats.applicationsOverTime().values();
        assertThat(values.stream().mapToLong(Long::longValue).sum()).isEqualTo(13);
        assertThat(values.stream().filter(v -> v > 0).count()).isEqualTo(13);

        UserDetails admin = admin();
        mockMvc.perform(get("/admin/statistics").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Statistics")));
    }

    // AC-A-D4-2: days=90 gives Applications 15 across 13 weekly buckets.
    @Test
    void ninetyDaysUsesWeeklyBuckets() {
        AdminStatistics stats = adminStatisticsService.getStatistics("90");

        assertThat(kpi(stats, "Applications")).isEqualTo("15");
        assertThat(stats.days()).isEqualTo(90);
        assertThat(stats.applicationsOverTime().labels()).hasSize(13);
    }

    // AC-A-D4-2 (second clause): an unparsable "days" value falls back to the 30-day view
    // (status 200, never the type-mismatch 404 handler, Section 7.9).
    @Test
    void invalidRangeFallsBackTo30() throws Exception {
        AdminStatistics stats = adminStatisticsService.getStatistics("abc");

        assertThat(stats.days()).isEqualTo(30);
        assertThat(kpi(stats, "Applications")).isEqualTo("13");

        UserDetails admin = admin();
        mockMvc.perform(get("/admin/statistics").param("days", "abc").with(user(admin)))
                .andExpect(status().isOk());
    }

    // AC-A-D4-3: Jobs by status (Pending approval 2, Approved 8, Rejected 1, Closed 1, in
    // that declared/displayed order) and the three named engagement metrics.
    @Test
    void engagementMetricsMatchSeedData() {
        AdminStatistics stats = adminStatisticsService.getStatistics("30");

        assertThat(stats.jobsByStatus().labels()).containsExactly("Pending approval", "Approved", "Rejected", "Closed");
        assertThat(stats.jobsByStatus().values()).containsExactly(2L, 8L, 1L, 1L);

        assertThat(metric(stats, "Seeker participation")).isEqualTo("4 of 6 (67%)");
        assertThat(metric(stats, "Active users (7 days)")).isEqualTo("6 of 10 (60%)");
        assertThat(metric(stats, "Approval turnaround")).isEqualTo("24.0 hours");
    }

    // AC-A-D4-4: the logins chart totals 8 for days=30 and 6 for days=7 (the 8 users who
    // have ever logged in, 6 of them within the last week, Section 13.2); a failed login
    // attempt never adds a LOGIN_SUCCESS row, so the totals are unchanged afterwards.
    @Test
    void loginsChartCountsLoginSuccessRows() throws Exception {
        assertThat(sum(adminStatisticsService.getStatistics("30").loginsOverTime())).isEqualTo(8);
        assertThat(sum(adminStatisticsService.getStatistics("7").loginsOverTime())).isEqualTo(6);

        failedLogin("priya@demo.local", "WrongPassword1")
                .andExpect(status().is3xxRedirection());

        assertThat(sum(adminStatisticsService.getStatistics("30").loginsOverTime())).isEqualTo(8);
    }

    private ResultActions failedLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .with(csrf()));
    }

    private long sum(ChartData chart) {
        return chart.values().stream().mapToLong(Long::longValue).sum();
    }

    private String kpi(AdminStatistics stats, String label) {
        for (KpiValue kpiValue : stats.kpis()) {
            if (kpiValue.label().equals(label)) {
                return kpiValue.value();
            }
        }
        throw new IllegalStateException("No KPI named " + label);
    }

    private String metric(AdminStatistics stats, String name) {
        for (EngagementMetric metric : stats.engagementMetrics()) {
            if (metric.name().equals(name)) {
                return metric.value();
            }
        }
        throw new IllegalStateException("No engagement metric named " + name);
    }

    private UserDetails admin() {
        return userDetailsService.loadUserByUsername("admin@jobportal.local");
    }
}
