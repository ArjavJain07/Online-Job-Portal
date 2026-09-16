package com.jobportal.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

// Unit tests for the "@fmt" template helper (Section 7.1). Formats only needs a Clock, so
// this is a plain unit test with no Spring context.
class FormatsTest {

    // 16 Sep 2026, 10:00 IST - matches FixedClockConfig, though this class doesn't use it.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-16T04:30:00Z"), ZoneId.of("Asia/Kolkata"));

    private final Formats formats = new Formats(CLOCK);

    @Test
    void inrGroupsByLakhsAndCrores() {
        assertThat(formats.inr(600_000)).isEqualTo("6,00,000");
        assertThat(formats.inr(100_000_000)).isEqualTo("10,00,00,000");
    }

    @Test
    void inrHandlesSmallAndNegativeAmounts() {
        assertThat(formats.inr(0)).isEqualTo("0");
        assertThat(formats.inr(999)).isEqualTo("999");
        assertThat(formats.inr(-600_000)).isEqualTo("-6,00,000");
    }

    @Test
    void agoIsEmptyForNull() {
        assertThat(formats.ago(null)).isEmpty();
    }

    @Test
    void agoIsTodayForTheCurrentDate() {
        assertThat(formats.ago(LocalDateTime.of(2026, 9, 16, 8, 0))).isEqualTo("today");
    }

    @Test
    void agoIsSingularForOneDay() {
        assertThat(formats.ago(LocalDateTime.of(2026, 9, 15, 11, 0))).isEqualTo("1 day ago");
    }

    @Test
    void agoIsPluralForMultipleDays() {
        assertThat(formats.ago(LocalDateTime.of(2026, 9, 11, 11, 0))).isEqualTo("5 days ago");
    }

    @Test
    void fileSizeUnderOneKilobyteIsInBytes() {
        assertThat(formats.fileSize(500)).isEqualTo("500 B");
    }

    @Test
    void fileSizeInKilobytesIsRounded() {
        assertThat(formats.fileSize(2048)).isEqualTo("2 KB");
    }

    @Test
    void fileSizeInMegabytesHasOneDecimal() {
        assertThat(formats.fileSize((long) (1.5 * 1024 * 1024))).isEqualTo("1.5 MB");
    }

    @Test
    void experienceIsFreshersWelcomeAtZeroOrBelow() {
        assertThat(formats.experience(0)).isEqualTo("Freshers welcome");
        assertThat(formats.experience(-1)).isEqualTo("Freshers welcome");
    }

    @Test
    void experienceShowsYearsWhenPositive() {
        assertThat(formats.experience(2)).isEqualTo("2+ years");
    }
}
