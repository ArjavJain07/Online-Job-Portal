package com.jobportal.web.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Forgot-password form (Section 16 #1). Format validation only - @Email rejects
// "not-an-address" the same way it would on any other form, which leaks nothing (it is a
// purely syntactic check, the same for every visitor regardless of what is in the
// database). Whether the address actually belongs to an account is looked up by
// PasswordResetService and never turned into a field or form error - see
// PasswordResetController for why.
public class ForgotPasswordForm {

    @NotBlank(message = "Please enter a valid email address.")
    @Email(message = "Please enter a valid email address.")
    @Size(max = 254, message = "Please enter a valid email address.")
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
