package com.jobportal.web.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// Change-password form (Section 6.1 P-5). Whether currentPassword actually matches the
// stored hash, and whether newPassword differs from it, needs the database, so both are
// checked by UserAccountService.changePassword and reported as a form error by
// AccountController (Section 7.2/7.3), not by an annotation here.
public class ChangePasswordForm {

    @NotBlank(message = "Current password is incorrect.")
    private String currentPassword;

    @NotBlank(message = "Password must be 8-64 characters (letters, digits and symbols, "
            + "no spaces) and contain at least one letter and one digit.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "Password must be 8-64 characters (letters, digits and symbols, "
                    + "no spaces) and contain at least one letter and one digit.")
    private String newPassword;

    private String confirmPassword;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
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
    // pattern as RegisterSeekerForm.
    @AssertTrue(message = "Passwords do not match.")
    public boolean isPasswordsMatching() {
        return newPassword == null ? confirmPassword == null : newPassword.equals(confirmPassword);
    }
}
