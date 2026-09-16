package com.jobportal.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The admin's reason for a decision (Section 6.2 A-F2): reject and take-down both use this
// same form, since both simply move a job to REJECTED with a reason the employer will see
// (Section 5.5).
public class JobReviewForm {

    public static final String REASON_MESSAGE = "Please give a reason of 10-500 characters. The employer will see it.";

    @NotBlank(message = REASON_MESSAGE)
    @Size(min = 10, max = 500, message = REASON_MESSAGE)
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
