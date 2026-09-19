package com.jobportal.domain.enums;

// The state of one scheduled interview (interview scheduling feature). Deliberately only
// TWO constants, and deliberately NOT a second lifecycle running alongside
// ApplicationStatus (Section 5.6).
//
// WHY THERE IS NO "COMPLETED" / "NO_SHOW" / "PASSED" CONSTANT
// The obvious next constants would all be judgements about how the interview WENT, and
// the application's own status already records exactly that: a candidate who did well
// moves INTERVIEW -> HIRED, one who did not moves INTERVIEW -> REJECTED, both through
// JobApplicationService.recordStatusChange and both written into the dated timeline the
// candidate reads (Section 5.6's "every change writes an ApplicationStatusChange row").
// An outcome constant here would be a second, unenforced copy of that same information,
// free to disagree with the status matrix ApplicationStatusTest checks over all 49 pairs.
// So this enum answers one question only - "is this appointment still on?" - and whether
// the interview is in the future or the past is read from the clock (Interview.isUpcoming)
// rather than stored, because a date that has passed is not an event anyone has to
// remember to record.
//
// CANCELLED is therefore reached in exactly two ways, both of which keep the row (never
// delete it) so the candidate can still see that something was arranged and then called
// off: the employer cancelling it themselves (InterviewService.cancel), and the automatic
// cancellation of a still-future interview when the application leaves the INTERVIEW stage
// for a final status (JobApplicationService.recordStatusChange - see the comment there).
public enum InterviewStatus {

    SCHEDULED("Scheduled"),
    CANCELLED("Cancelled");

    private final String label;

    InterviewStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
