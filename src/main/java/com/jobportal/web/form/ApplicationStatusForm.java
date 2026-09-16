package com.jobportal.web.form;

import com.jobportal.domain.enums.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Employer "Change status" form on the application detail page (Section 6.3 E-F2). The
// select only ever offers currentStatus.employerOptions() (built in the template, not
// here), but a stale page or a hand-crafted POST can still submit anything: an
// unparsable value becomes a plain binding error on this field ("Please choose a new
// status." never fires in that case, Spring's own "typeMismatch" message does instead),
// and a structurally valid but disallowed transition is caught by
// JobApplicationService.changeStatus as a BusinessRuleException (Section 6.3 E-F2
// business rule 2).
public class ApplicationStatusForm {

    @NotNull(message = "Please choose a new status.")
    private ApplicationStatus status;

    // Optional (Section 6.3 E-F2): shown to the candidate on their own timeline.
    @Size(max = 500, message = "Note must be 500 characters or fewer.")
    private String noteToCandidate;

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public String getNoteToCandidate() {
        return noteToCandidate;
    }

    public void setNoteToCandidate(String noteToCandidate) {
        this.noteToCandidate = noteToCandidate;
    }
}
