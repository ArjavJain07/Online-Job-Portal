package com.jobportal.web.form;

import jakarta.validation.constraints.Size;

// The one optional sentence an employer can add when calling an interview off (interview
// scheduling feature). A class of its own rather than a bare @RequestParam so the reason
// gets a real length constraint and a real field error, the same way
// ApplicationStatusForm.noteToCandidate and JobReviewForm's rejection reason do - and 500
// characters to match both of those, since it is the same kind of text written by the same
// person for the same reader.
//
// The reason is shown to the CANDIDATE (on their application page and in the cancellation
// email), unlike JobApplication.internalNote, which is private to the employer - so it is
// named "reason", not "note", and the form label says so explicitly.
public class InterviewCancelForm {

    @Size(max = 500, message = "Reason must be 500 characters or fewer.")
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
