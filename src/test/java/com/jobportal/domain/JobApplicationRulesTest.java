package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

// Unit tests for JobApplication.isUpdatedForSeeker and getReference (Section 5.6).
class JobApplicationRulesTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 16, 10, 0);

    @Test
    void notUpdatedWhileStillApplied() {
        JobApplication application = new JobApplication();
        // statusChangedAt stays null until the first status change after APPLIED.
        assertThat(application.isUpdatedForSeeker()).isFalse();
    }

    @Test
    void updatedWhenChangedAfterNeverViewed() {
        JobApplication application = new JobApplication();
        application.setStatusChangedAt(T);
        application.setSeekerLastViewedAt(null);
        assertThat(application.isUpdatedForSeeker()).isTrue();
    }

    @Test
    void notUpdatedWhenViewedAfterTheChange() {
        JobApplication application = new JobApplication();
        application.setStatusChangedAt(T);
        application.setSeekerLastViewedAt(T.plusMinutes(1));
        assertThat(application.isUpdatedForSeeker()).isFalse();
    }

    @Test
    void updatedWhenChangedAfterTheLastView() {
        JobApplication application = new JobApplication();
        application.setStatusChangedAt(T);
        application.setSeekerLastViewedAt(T.minusMinutes(1));
        assertThat(application.isUpdatedForSeeker()).isTrue();
    }

    // The withdrawal case (Section 13.5, A8/A15): seekerLastViewedAt is set to the exact
    // same instant as statusChangedAt, so the seeker's own action never raises a badge.
    @Test
    void notUpdatedWhenViewedAtExactlyTheSameInstantAsTheChange() {
        JobApplication application = new JobApplication();
        application.setStatusChangedAt(T);
        application.setSeekerLastViewedAt(T);
        assertThat(application.isUpdatedForSeeker()).isFalse();
    }

    @Test
    void referenceIsZeroPaddedToFiveDigits() {
        JobApplication application = new JobApplication();
        application.setId(42L);
        assertThat(application.getReference()).isEqualTo("APP-00042");
    }

    @Test
    void referenceForASingleDigitId() {
        JobApplication application = new JobApplication();
        application.setId(1L);
        assertThat(application.getReference()).isEqualTo("APP-00001");
    }

    @Test
    void referenceIsNotTruncatedForIdsLongerThanFiveDigits() {
        JobApplication application = new JobApplication();
        application.setId(123456L);
        assertThat(application.getReference()).isEqualTo("APP-123456");
    }
}
