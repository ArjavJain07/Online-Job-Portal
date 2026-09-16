package com.jobportal.web.form;

import jakarta.validation.constraints.Size;

// Employer "Private note" form on the application detail page (Section 6.3 E-F2, Section
// 7.3 form table: "internalNote at most 1000"). Optional - saving a blank note clears it
// - and, unlike noteToCandidate, this text is never shown to the seeker and never logged
// (Section 6.3 E-F2 business rule 6, D-27).
public class InternalNoteForm {

    @Size(max = 1000, message = "Note must be 1000 characters or fewer.")
    private String internalNote;

    public String getInternalNote() {
        return internalNote;
    }

    public void setInternalNote(String internalNote) {
        this.internalNote = internalNote;
    }
}
