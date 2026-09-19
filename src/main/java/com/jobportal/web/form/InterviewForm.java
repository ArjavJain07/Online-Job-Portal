package com.jobportal.web.form;

import com.jobportal.domain.enums.InterviewMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

// The employer's "Schedule interview" form, and the identical "Reschedule / update" one
// (interview scheduling feature). One form class for both actions because the employer is
// answering exactly the same four questions in both cases - when, how, where, anything
// else - and a second class would only be the first one copied with a different name.
//
// DATE AND TIME AS TWO FIELDS, NOT ONE datetime-local
// <input type="date"> is already how this project asks for a date (employer/job-form.html
// and two more), and Spring's default ISO binding for java.time types is what makes it
// work with no @DateTimeFormat anywhere - <input type="time"> binds to LocalTime through
// the same default. A single datetime-local control would be one field instead of two,
// but it would also be the only date control in the application that behaves differently
// from the others, and it degrades to a free-text box on browsers that do not implement
// it, where "23/9/26 3pm" would become a type mismatch the employer cannot make sense of.
// scheduledAt() below recombines the two.
//
// WHAT IS *NOT* VALIDATED HERE
// "Not in the past" is deliberately absent. It depends on what time it is, and Section
// 7.10 puts every "what time is it" decision behind the injected Clock, which a bean
// validation constraint has no access to - the same reason JobService checks its deadline
// range in the service (JobService.DEADLINE_RANGE_MESSAGE) rather than putting @Future on
// JobForm.applicationDeadline. InterviewService.validateSlot does it, as a
// BusinessRuleException, and its comment explains what the window is and why.
public class InterviewForm {

    @NotNull(message = "Please choose an interview date.")
    private LocalDate date;

    @NotNull(message = "Please choose an interview time.")
    private LocalTime time;

    @NotNull(message = "Please choose how the interview will happen.")
    private InterviewMode mode;

    // Optional or required depending on mode - see locationProvidedForMode() below.
    @Size(max = 300, message = "Location or joining link must be 300 characters or fewer.")
    private String location;

    @Size(max = 1000, message = "Notes must be 1000 characters or fewer.")
    private String notes;

    // The two form fields as the single instant they describe, or null while either half is
    // still missing (in which case the two @NotNull messages above are what the employer
    // sees, and no caller gets this far). This is a wall-clock time with no zone attached
    // on purpose: the zone is not the employer's to type, it is the site's, and
    // InterviewService reads it from the injected Clock - see Interview's class comment.
    public LocalDateTime scheduledAt() {
        return date == null || time == null ? null : LocalDateTime.of(date, time);
    }

    // Section 7.3-style cross-field check, rendered by the template as
    // th:errors="*{locationProvidedForMode}" - the same shape JobForm.salaryRangeValid
    // already uses. The rule itself belongs to the mode, not to this form, so it is read
    // off InterviewMode.isDetailRequired(): a video interview with no joining link and an
    // on-site interview with no address are both instructions a candidate cannot act on,
    // while a phone interview may legitimately give nothing ("we will call the number on
    // your profile" is complete). A null mode passes here so that only its own @NotNull
    // message fires, exactly as JobForm's own @AssertTrue methods let nulls through.
    @AssertTrue(message = "Add the joining link or address for this interview mode.")
    public boolean isLocationProvidedForMode() {
        if (mode == null || !mode.isDetailRequired()) {
            return true;
        }
        return location != null && !location.isBlank();
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalTime getTime() {
        return time;
    }

    public void setTime(LocalTime time) {
        this.time = time;
    }

    public InterviewMode getMode() {
        return mode;
    }

    public void setMode(InterviewMode mode) {
        this.mode = mode;
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
}
