package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.enums.ApplicationStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// Unit tests for the ApplicationStatus lifecycle (Section 5.6). The expected table below is
// copied from the plan's state diagram, independently of ApplicationStatus.allowedNext(),
// so a change to the production switch statement is actually caught.
class ApplicationStatusTest {

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> EXPECTED_TRANSITIONS =
            new EnumMap<>(ApplicationStatus.class);

    static {
        EXPECTED_TRANSITIONS.put(ApplicationStatus.APPLIED, EnumSet.of(ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.UNDER_REVIEW, EnumSet.of(ApplicationStatus.SHORTLISTED,
                ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.SHORTLISTED, EnumSet.of(ApplicationStatus.INTERVIEW,
                ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.INTERVIEW,
                EnumSet.of(ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.HIRED, EnumSet.noneOf(ApplicationStatus.class));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        EXPECTED_TRANSITIONS.put(ApplicationStatus.WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));
    }

    // Every (from, to) pair: 7 statuses x 7 statuses = 49, matching 12.2's "all 49 from/to
    // pairs".
    static Stream<Arguments> allPairs() {
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (ApplicationStatus from : ApplicationStatus.values()) {
            for (ApplicationStatus to : ApplicationStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.build();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void canTransitionToMatchesTheLifecycleTable(ApplicationStatus from, ApplicationStatus to) {
        boolean expected = EXPECTED_TRANSITIONS.get(from).contains(to);
        assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("com.jobportal.domain.ApplicationStatusTest#allStatuses")
    void employerOptionsNeverContainsWithdrawn(ApplicationStatus status) {
        assertThat(status.employerOptions()).doesNotContain(ApplicationStatus.WITHDRAWN);
    }

    @ParameterizedTest
    @MethodSource("com.jobportal.domain.ApplicationStatusTest#allStatuses")
    void employerOptionsIsAllowedNextMinusWithdrawn(ApplicationStatus status) {
        Set<ApplicationStatus> expected = EnumSet.copyOf(EXPECTED_TRANSITIONS.get(status));
        expected.remove(ApplicationStatus.WITHDRAWN);
        assertThat(status.employerOptions()).isEqualTo(expected);
    }

    static Stream<ApplicationStatus> allStatuses() {
        return Stream.of(ApplicationStatus.values());
    }

    @ParameterizedTest
    @MethodSource("com.jobportal.domain.ApplicationStatusTest#allStatuses")
    void activeAndFinalAreOpposites(ApplicationStatus status) {
        assertThat(status.isActive()).isEqualTo(!status.isFinal());
        assertThat(status.isFinal()).isEqualTo(EXPECTED_TRANSITIONS.get(status).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void labels() {
        assertThat(ApplicationStatus.APPLIED.getLabel()).isEqualTo("Applied");
        assertThat(ApplicationStatus.APPLIED.getSeekerLabel()).isEqualTo("Applied");
        assertThat(ApplicationStatus.UNDER_REVIEW.getLabel()).isEqualTo("Under review");
        assertThat(ApplicationStatus.UNDER_REVIEW.getSeekerLabel()).isEqualTo("Under review");
        assertThat(ApplicationStatus.SHORTLISTED.getLabel()).isEqualTo("Shortlisted");
        assertThat(ApplicationStatus.INTERVIEW.getLabel()).isEqualTo("Interview");
        assertThat(ApplicationStatus.HIRED.getLabel()).isEqualTo("Hired");
        assertThat(ApplicationStatus.WITHDRAWN.getLabel()).isEqualTo("Withdrawn");
        assertThat(ApplicationStatus.WITHDRAWN.getSeekerLabel()).isEqualTo("Withdrawn");

        // The one status with two different labels: employers/admins see "Rejected", the
        // seeker sees the softer "Not selected" (Section 5.6).
        assertThat(ApplicationStatus.REJECTED.getLabel()).isEqualTo("Rejected");
        assertThat(ApplicationStatus.REJECTED.getSeekerLabel()).isEqualTo("Not selected");
    }
}
