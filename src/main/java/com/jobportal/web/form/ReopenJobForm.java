package com.jobportal.web.form;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

// Reopens a closed job with a new deadline (Section 6.3 E-D4, 7.3 form table: "JobForm,
// ReopenJobForm | E-F1, E-D1, E-D4"). Posted from both employer/job-detail.html and
// employer/job-history.html. The today-to-180-days range depends on "today" from the
// injected Clock, so - like JobForm.applicationDeadline - it is checked in
// JobService.reopen, not with a static annotation here; @NotNull only catches a blank
// field before that.
public class ReopenJobForm {

    // Annotated for the same reason as JobForm.applicationDeadline, even though neither
    // reopen input is pre-filled today (both are bare name="newDeadline" controls with
    // no value, in employer/job-detail.html and employer/job-history.html, so there is
    // nothing for Spring to print and the bug cannot show). If either is ever given a
    // th:field or a default, the locale-short default would render a value that
    // <input type="date"> rejects and the field would silently open empty.
    @NotNull(message = "The new deadline must be between today and 180 days from now.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate newDeadline;

    public LocalDate getNewDeadline() {
        return newDeadline;
    }

    public void setNewDeadline(LocalDate newDeadline) {
        this.newDeadline = newDeadline;
    }
}
