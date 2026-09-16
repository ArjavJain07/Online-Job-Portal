package com.jobportal.web.form;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

// Reopens a closed job with a new deadline (Section 6.3 E-D4, 7.3 form table: "JobForm,
// ReopenJobForm | E-F1, E-D1, E-D4"). Posted from both employer/job-detail.html and
// employer/job-history.html. The today-to-180-days range depends on "today" from the
// injected Clock, so - like JobForm.applicationDeadline - it is checked in
// JobService.reopen, not with a static annotation here; @NotNull only catches a blank
// field before that.
public class ReopenJobForm {

    @NotNull(message = "The new deadline must be between today and 180 days from now.")
    private LocalDate newDeadline;

    public LocalDate getNewDeadline() {
        return newDeadline;
    }

    public void setNewDeadline(LocalDate newDeadline) {
        this.newDeadline = newDeadline;
    }
}
