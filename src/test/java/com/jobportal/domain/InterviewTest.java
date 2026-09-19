package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.enums.InterviewMode;
import com.jobportal.domain.enums.InterviewStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

// The derived state on Interview, tested without a Spring context the same way JobTest and
// PasswordResetTokenTest cover Job#displayStatus and PasswordResetToken#isUsable - these
// are pure functions of the row plus a "now" that is handed in, which is exactly what
// makes them testable at all (Section 7.10).
//
// The three things checked here are the three the rest of the feature trusts completely:
// isUpcoming decides whether a status change cancels the appointment, isPast decides
// whether a page calls it history, and getWhenText is the single definition of an
// interview's time that both pages and all three emails render.
class InterviewTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 10, 0);

    @Test
    void scheduledInTheFutureIsUpcomingAndNotPast() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 23, 15, 30));

        assertThat(interview.isUpcoming(NOW)).isTrue();
        assertThat(interview.isPast(NOW)).isFalse();
        assertThat(interview.isCancelled()).isFalse();
    }

    // The case that must keep working with no sweep, no nightly job and no migration: the
    // row is untouched and only the answer changes, because "past" is a fact about the
    // clock rather than a stored state (see InterviewStatus).
    @Test
    void scheduledInThePastIsPastAndNoLongerUpcoming() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 14, 11, 0));

        assertThat(interview.isUpcoming(NOW)).isFalse();
        assertThat(interview.isPast(NOW)).isTrue();
    }

    // The boundary: an interview starting exactly now is not something anyone can still be
    // warned about, so it counts as past rather than upcoming. It matters because this is
    // the comparison that decides whether a rejection cancels the appointment.
    @Test
    void anInterviewStartingExactlyNowCountsAsPast() {
        Interview interview = scheduled(NOW);

        assertThat(interview.isUpcoming(NOW)).isFalse();
        assertThat(interview.isPast(NOW)).isTrue();
    }

    // A cancelled interview is neither upcoming nor past - both questions are about an
    // appointment that is still on, and this one is not. Without this, a cancelled future
    // interview would report isUpcoming() and be cancelled a second time.
    @Test
    void cancelledIsNeitherUpcomingNorPast() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 23, 15, 30));

        interview.cancel(NOW, "The panel is unavailable that week.");

        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
        assertThat(interview.isCancelled()).isTrue();
        assertThat(interview.isUpcoming(NOW)).isFalse();
        assertThat(interview.isPast(NOW)).isFalse();
        assertThat(interview.getCancelledAt()).isEqualTo(NOW);
        assertThat(interview.getCancellationReason()).isEqualTo("The panel is unavailable that week.");
    }

    // The zone decision, at its source: the stored wall clock is rendered verbatim and the
    // zone is NAMED beside it, never converted away. Both the abbreviation and the full id
    // appear, because "IST" alone means three different things and this is the one string
    // in the application where being misread costs somebody an interview.
    @Test
    void whenTextRendersTheStoredWallClockAndNamesItsZone() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 23, 15, 30));

        assertThat(interview.getWhenText()).startsWith("23 Sep 2026, 3:30 PM").endsWith("(Asia/Kolkata)");
    }

    // A row written in one zone keeps saying that zone even when read somewhere else: the
    // digits and the label travel together, so a server move cannot silently shift every
    // historical appointment by hours.
    @Test
    void whenTextUsesTheRowsOwnStoredZoneNotTheMachines() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 23, 15, 30));
        interview.setTimeZone("Europe/London");

        assertThat(interview.getWhenText()).startsWith("23 Sep 2026, 3:30 PM").endsWith("(Europe/London)");
    }

    @Test
    void previousWhenTextIsNullUntilTheInterviewHasActuallyMoved() {
        Interview interview = scheduled(LocalDateTime.of(2026, 9, 23, 15, 30));
        assertThat(interview.getPreviousWhenText()).isNull();

        interview.setPreviousScheduledAt(LocalDateTime.of(2026, 9, 21, 10, 0));
        assertThat(interview.getPreviousWhenText()).startsWith("21 Sep 2026, 10:00 AM").endsWith("(Asia/Kolkata)");
    }

    private Interview scheduled(LocalDateTime at) {
        Interview interview = new Interview();
        interview.setStatus(InterviewStatus.SCHEDULED);
        interview.setMode(InterviewMode.VIDEO);
        interview.setScheduledAt(at);
        interview.setTimeZone("Asia/Kolkata");
        interview.setLocation("https://meet.example.com/abc-defg-hij");
        interview.setCreatedAt(NOW);
        interview.setUpdatedAt(NOW);
        return interview;
    }
}
