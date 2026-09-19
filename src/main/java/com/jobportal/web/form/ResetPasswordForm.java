package com.jobportal.web.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// Reset-password form (Section 16 #1). token travels as a hidden field, round-tripped from
// the GET /reset-password?token=... link (PasswordResetController); the password rule is
// character-for-character the one ChangePasswordForm and RegisterSeekerForm already use
// (Section 4.3), so a reset password is never held to a different standard than any other.
public class ResetPasswordForm {

    @NotBlank
    private String token;

    @NotBlank(message = "Password must be 8-64 characters (letters, digits and symbols, "
            + "no spaces) and contain at least one letter and one digit.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "Password must be 8-64 characters (letters, digits and symbols, "
                    + "no spaces) and contain at least one letter and one digit.")
    private String newPassword;

    private String confirmPassword;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    // Cross-field check shown with th:errors="*{passwordsMatching}" (Section 7.3), same
    // pattern as ChangePasswordForm/RegisterSeekerForm.
    @AssertTrue(message = "Passwords do not match.")
    public boolean isPasswordsMatching() {
        return newPassword == null ? confirmPassword == null : newPassword.equals(confirmPassword);
    }
}
