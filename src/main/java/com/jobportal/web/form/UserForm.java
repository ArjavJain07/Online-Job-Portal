package com.jobportal.web.form;

import com.jobportal.domain.enums.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Admin create/edit user form (Section 6.2 A-F1). One class handles both GET
// /admin/users/new and GET /admin/users/{id}/edit: newPassword has no @NotBlank here
// because it is optional on edit (blank keeps the current password); AdminUserController
// adds the "required on create" check itself with the same PASSWORD_MESSAGE, since Bean
// Validation has no create/edit mode of its own (no validation groups are used anywhere
// else in this codebase, Section 7.3).
public class UserForm {

    public static final String PASSWORD_MESSAGE =
            "Please set a temporary password (8-64 characters, no spaces, at least one letter and one digit).";

    @NotBlank(message = "Please enter a name (2-100 characters).")
    @Size(min = 2, max = 100, message = "Please enter a name (2-100 characters).")
    private String fullName;

    @NotBlank(message = "Please enter a valid email address.")
    @Email(message = "Please enter a valid email address.")
    @Size(max = 254, message = "Please enter a valid email address.")
    private String email;

    @NotNull(message = "Please choose a role.")
    private Role role;

    private boolean enabled;

    private String companyName;

    // Printable ASCII only, same pattern as registration (Section 4.3). Null (a blank
    // submission, turned into null by StringTrimmerEditor) always passes: @Pattern skips
    // null values, which is exactly what "optional on edit" needs.
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$", message = PASSWORD_MESSAGE)
    private String newPassword;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    // Company name is required (2-120 characters) only when the role is EMPLOYER
    // (Section 6.2 A-F1 field table). A null role fails its own @NotNull check, so this
    // simply passes rather than piling on a second, confusing error.
    @AssertTrue(message = "Company name is required for employer accounts.")
    public boolean isCompanyNameValid() {
        if (role != Role.EMPLOYER) {
            return true;
        }
        return companyName != null && companyName.trim().length() >= 2 && companyName.trim().length() <= 120;
    }
}
