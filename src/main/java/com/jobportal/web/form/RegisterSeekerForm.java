package com.jobportal.web.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Job seeker registration (Section 6.1 P-3, rules in 4.3). RegisterEmployerForm extends
// this and adds the company fields, so the shared name/email/password rules live in one
// place. GlobalModelAttributes#initBinder trims every String and turns a blank one into
// null before it reaches these annotations (Section 7.3).
public class RegisterSeekerForm {

    @NotBlank(message = "Please enter your full name (2-100 characters).")
    @Size(min = 2, max = 100, message = "Please enter your full name (2-100 characters).")
    private String fullName;

    @NotBlank(message = "Please enter a valid email address.")
    @Email(message = "Please enter a valid email address.")
    @Size(max = 254, message = "Please enter a valid email address.")
    private String email;

    // Printable ASCII only (4.3): every allowed character is one byte in UTF-8, so 64
    // characters are at most 64 bytes, safely under BCryptPasswordEncoder's 72-byte limit.
    @NotBlank(message = "Password must be 8-64 characters (letters, digits and symbols, "
            + "no spaces) and contain at least one letter and one digit.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "Password must be 8-64 characters (letters, digits and symbols, "
                    + "no spaces) and contain at least one letter and one digit.")
    private String password;

    private String confirmPassword;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    // Cross-field check shown with th:errors="*{passwordsMatching}" (Section 7.3). Passes
    // when password is missing too, so a blank password shows only its own @NotBlank
    // error instead of a second, confusing "Passwords do not match."
    @AssertTrue(message = "Passwords do not match.")
    public boolean isPasswordsMatching() {
        return password == null ? confirmPassword == null : password.equals(confirmPassword);
    }
}
