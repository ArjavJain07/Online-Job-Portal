package com.jobportal.domain;

import com.jobportal.domain.enums.InterviewMode;
import com.jobportal.domain.enums.InterviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

// The when/where/how of one interview, attached to a job application that is already at
// ApplicationStatus.INTERVIEW (Section 5.6). Until this existed, moving a candidate to
// that stage recorded the stage and nothing else, and the employer had to say the actual
// arrangements in a message (Section 6.5.1) that no page could show as an appointment.
//
// ===========================================================================
// ATTACHED TO the INTERVIEW stage - it never DRIVES a status change
// ===========================================================================
// Scheduling writes only this table. It does not move the application's status, and
// InterviewService has no path that could: the only way an application ever reaches
// INTERVIEW is still the employer's existing "Change status" form ->
// JobApplicationService.changeStatus -> recordStatusChange, the one method that checks
// ApplicationStatus.canTransitionTo (14 of 49 pairs are legal, Section 5.6, and
// ApplicationStatusTest is parameterised over all 49) and writes the
// ApplicationStatusChange row the candidate's dated timeline is built from.
//
// The alternative - "schedule an interview" implicitly moving SHORTLISTED -> INTERVIEW -
// was rejected for two reasons. It would need a second entry point into the status
// machine, which Section 5.6's whole design says there must not be; and it would be a
// half-rule anyway, because the matrix allows INTERVIEW only from SHORTLISTED, so
// scheduling from APPLIED or UNDER_REVIEW would still have to be refused and the employer
// would still have to understand the pipeline. Requiring the stage first keeps exactly
// one explanation of what "Interview" means, and the timeline row that already exists at
// that stage is the interview's own audit trail.
//
// ===========================================================================
// ONE ROW PER APPLICATION (uk_interview_application)
// ===========================================================================
// Rescheduling updates this row in place rather than inserting a second one, and
// cancelling keeps it (status = CANCELLED) rather than deleting it - so the candidate's
// page can always answer "what, if anything, is arranged?" from a single row, and can
// still say that something WAS arranged and was called off. previousScheduledAt and
// rescheduleCount carry just enough of the history for the candidate to see that the time
// moved and what it moved from, without a second history table: unlike a status change,
// which is a decision someone must be accountable for forever, a superseded interview slot
// stops mattering the moment the new one is agreed. Multi-round interviews (a separate row
// per round) are out of scope - the application lifecycle has one INTERVIEW stage, so a
// second round today is a reschedule of the same appointment.
//
// ===========================================================================
// TIME ZONE: stored as wall-clock time PLUS the zone it was entered in, never converted
// ===========================================================================
// scheduledAt is a LocalDateTime in a timestamp(6) column, exactly like every other
// timestamp in this schema (Section 10.7's shared form) - but unlike appliedAt or
// changedAt, an interview time is read by a HUMAN who has to be somewhere at it, so a
// bare local time with an unstated zone is a missed interview waiting to happen.
//
// The decision, therefore, is:
//   * store the wall-clock time the employer typed, unchanged;
//   * store the zone that wall clock belongs to (timeZone, from the injected Clock's zone
//     at the moment of scheduling - Section 7.10: no LocalDateTime.now() in business
//     logic, and no ZoneId.systemDefault() here either);
//   * NEVER convert on the way in or the way out - whenText() below renders the stored
//     digits verbatim and names the zone beside them.
//
// Storing the zone rather than assuming it is what makes a STORED interview still correct
// later: a row written while the server ran in Asia/Kolkata still says Asia/Kolkata after
// the server is moved, instead of silently re-reading as the new zone and moving every
// historical appointment by hours. Not converting is what makes it correct NOW: the
// employer sees back exactly the digits they typed, and the candidate sees those same
// digits with the zone attached, so neither side is ever shown a time the other did not
// mean. Converting into each viewer's own zone would be the richer feature, but this
// application has nowhere to learn a viewer's zone from (there is no such field on User or
// SeekerProfile) - and guessing it from the browser would mean a time that renders
// differently for the two people who must meet at it. Naming the zone is the honest
// version of what this app actually knows.
@Entity
@Table(name = "interviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_interview_application", columnNames = "application_id"))
public class Interview {

    // "23 Sep 2026, 3:30 PM" - the project's usual "dd MMM yyyy" (Section 7.1, every other
    // date on these pages) with a time added, since the time is the whole point here.
    private static final DateTimeFormatter WHEN_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewMode mode;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    // The ZoneId id (not the abbreviation) scheduledAt's wall clock belongs to, e.g.
    // "Asia/Kolkata". 60 characters: the longest id in the IANA database today is a little
    // over 30 ("America/Argentina/ComodRivadavia"), and this column is written from
    // ZoneId#getId, never from user input, so the length only has to survive the database
    // growing new names rather than anything adversarial.
    @Column(nullable = false, length = 60)
    private String timeZone;

    // The joining link, address or phone number - what it means depends on mode, which is
    // why InterviewMode carries its own detailLabel. Nullable because
    // InterviewMode.PHONE.isDetailRequired() is false (see that enum).
    @Column(length = 300)
    private String location;

    @Column(length = 1000)
    private String notes;

    // The slot this interview was moved FROM, set only by a reschedule. Kept so the
    // candidate is told the time CHANGED rather than just being shown a different time -
    // someone who has already blocked out the old slot needs to recognise which entry in
    // their calendar is now wrong.
    private LocalDateTime previousScheduledAt;

    @Column(nullable = false)
    private int rescheduleCount = 0;

    @Column(length = 500)
    private String cancellationReason;

    private LocalDateTime cancelledAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // No @PrePersist default for createdAt, unlike JobApplication.appliedAt and
    // JobView.viewedAt: both of those can be written by a path with no Clock to hand, so a
    // fallback earns its keep there. Every row of this table is written by InterviewService
    // inside one method that already holds the injected Clock (Section 7.10), so a
    // LocalDateTime.now() fallback here could only ever mask the bug of forgetting to set
    // it - and would do so by quietly writing a time from the machine clock into a table
    // whose entire point is that its times are trustworthy.

    // ---- Derived state, defined once here (Section 3.5 design rule 4) ----

    // "Is this appointment still on, and still ahead of us?" - the question that decides
    // whether the candidate's pages show a live appointment or a historical note, and the
    // one JobApplicationService checks before auto-cancelling on a final status. `now` is
    // passed in rather than read from a static clock so callers stay testable (Section
    // 7.10), and it is compared against the stored wall clock in this row's own zone: both
    // sides of that comparison come from the same injected Clock, whose zone IS timeZone
    // for any row this application wrote.
    public boolean isUpcoming(LocalDateTime now) {
        return status == InterviewStatus.SCHEDULED && scheduledAt.isAfter(now);
    }

    // A scheduled interview whose time has passed. NOT a separate stored state (see
    // InterviewStatus): "past" is a fact about the clock, so it is derived, which is also
    // what makes the "a stored interview naturally becomes past and must still display
    // correctly" case work with no migration, sweep or nightly job - the row is untouched
    // and only the sentence around it changes.
    public boolean isPast(LocalDateTime now) {
        return status == InterviewStatus.SCHEDULED && !scheduledAt.isAfter(now);
    }

    public boolean isCancelled() {
        return status == InterviewStatus.CANCELLED;
    }

    // "23 Sep 2026, 3:30 PM IST (Asia/Kolkata)". One definition, used by the employer page,
    // both candidate pages and all three emails, so the time a candidate reads in their
    // inbox is character-for-character the one on their application page.
    //
    // Both the abbreviation and the full id are shown on purpose: "IST" is what a person
    // recognises at a glance, but it is genuinely ambiguous (India, Ireland and Israel all
    // use it), and this is the one string in the application where being misread costs
    // someone an interview. getDisplayName falls back to a "GMT+05:30" style offset for
    // any zone with no short name, which is unambiguous too.
    public String getWhenText() {
        return formatWhen(scheduledAt);
    }

    // The same text for the slot this interview was moved from, or null when it has never
    // been rescheduled - so a template can guard on it with a plain th:if.
    public String getPreviousWhenText() {
        return previousScheduledAt == null ? null : formatWhen(previousScheduledAt);
    }

    private String formatWhen(LocalDateTime when) {
        ZoneId zone = ZoneId.of(timeZone);
        return WHEN_FORMAT.format(when) + " " + zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                + " (" + zone.getId() + ")";
    }

    // ---- Mutations, kept here so both callers of "cancel" agree on what cancelling IS ----

    // Used by the employer's own cancellation AND by the automatic one that fires when an
    // application leaves the INTERVIEW stage (JobApplicationService.recordStatusChange).
    // The two differ only in the reason text and which email goes out; what a cancelled row
    // LOOKS like must not differ at all, so the field writes live here rather than being
    // typed out twice.
    public void cancel(LocalDateTime now, String reason) {
        this.status = InterviewStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancellationReason = reason;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public JobApplication getApplication() {
        return application;
    }

    public void setApplication(JobApplication application) {
        this.application = application;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public void setStatus(InterviewStatus status) {
        this.status = status;
    }

    public InterviewMode getMode() {
        return mode;
    }

    public void setMode(InterviewMode mode) {
        this.mode = mode;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDateTime getPreviousScheduledAt() {
        return previousScheduledAt;
    }

    public void setPreviousScheduledAt(LocalDateTime previousScheduledAt) {
        this.previousScheduledAt = previousScheduledAt;
    }

    public int getRescheduleCount() {
        return rescheduleCount;
    }

    public void setRescheduleCount(int rescheduleCount) {
        this.rescheduleCount = rescheduleCount;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
