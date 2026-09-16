package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.dto.ChartData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

// Unit tests for DateBuckets (Section 7.6): the days=7/30/90 parsing and the bucketing math.
class DateBucketsTest {

    // Matches FixedClockConfig's date, though this class needs no Spring context.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Test
    void normaliseDaysAcceptsOnly7or30or90() {
        assertThat(DateBuckets.normaliseDays("7")).isEqualTo(7);
        assertThat(DateBuckets.normaliseDays("30")).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("90")).isEqualTo(90);
    }

    @Test
    void normaliseDaysFallsBackTo30ForAnythingElse() {
        assertThat(DateBuckets.normaliseDays(null)).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("")).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("   ")).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("abc")).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("14")).isEqualTo(30);
        assertThat(DateBuckets.normaliseDays("-7")).isEqualTo(30);
    }

    @Test
    void normaliseDaysTrimsWhitespace() {
        assertThat(DateBuckets.normaliseDays(" 7 ")).isEqualTo(7);
    }

    @Test
    void sevenDayWindowHasSevenDailyBucketsWithZeroFill() {
        List<LocalDateTime> timestamps = List.of(
                TODAY.minusDays(6).atTime(9, 0), // first bucket
                TODAY.atTime(23, 59)); // last bucket

        ChartData chart = DateBuckets.count(timestamps, TODAY, 7, "Applications");

        assertThat(chart.label()).isEqualTo("Applications");
        assertThat(chart.labels()).hasSize(7);
        assertThat(chart.values()).hasSize(7);
        assertThat(chart.values().get(0)).isEqualTo(1L);
        assertThat(chart.values().get(6)).isEqualTo(1L);
        assertThat(chart.values().subList(1, 6)).allMatch(v -> v == 0L);
        assertThat(chart.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
    }

    @Test
    void timestampJustBeforeTheSevenDayWindowIsIgnored() {
        List<LocalDateTime> timestamps = List.of(TODAY.minusDays(7).atTime(23, 59));

        ChartData chart = DateBuckets.count(timestamps, TODAY, 7, "Applications");

        assertThat(chart.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void thirtyDayWindowHasThirtyDailyBucketsAllZeroWhenEmpty() {
        ChartData chart = DateBuckets.count(List.of(), TODAY, 30, "Applications");

        assertThat(chart.labels()).hasSize(30);
        assertThat(chart.values()).hasSize(30).allMatch(v -> v == 0L);
    }

    @Test
    void ninetyDaysGivesThirteenWeeklyBucketsEndingToday() {
        List<LocalDateTime> timestamps = List.of(
                TODAY.minusDays(90).atTime(0, 0), // first day of the first weekly bucket
                TODAY.atTime(23, 59)); // last day of the last weekly bucket

        ChartData chart = DateBuckets.count(timestamps, TODAY, 90, "Applications");

        assertThat(chart.labels()).hasSize(13);
        assertThat(chart.values()).hasSize(13);
        assertThat(chart.values().get(0)).isEqualTo(1L);
        assertThat(chart.values().get(12)).isEqualTo(1L);
        assertThat(chart.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
    }

    @Test
    void timestampJustBeforeTheNinetyDayWindowIsIgnored() {
        List<LocalDateTime> timestamps = List.of(TODAY.minusDays(91).atTime(23, 59));

        ChartData chart = DateBuckets.count(timestamps, TODAY, 90, "Applications");

        assertThat(chart.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void dailyBucketLabelUsesDayAndAbbreviatedMonth() {
        ChartData chart = DateBuckets.count(List.of(), TODAY, 7, "Applications");
        assertThat(chart.labels().get(6)).isEqualTo("16 Sep");
        assertThat(chart.labels().get(0)).isEqualTo("10 Sep");
    }
}
