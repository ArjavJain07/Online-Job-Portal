package com.jobportal.web.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

// Company profile form (Section 6.3 EP). fullName is the contact person shown throughout
// the app for this account, not the company name; the two other optional fields follow
// the same rules RegisterEmployerForm already uses for the same columns (Section 4.3), so
// the wording stays consistent between registration and editing.
public class EmployerProfileForm {

    @NotBlank(message = "Please enter a contact name (2-100 characters).")
    @Size(min = 2, max = 100, message = "Please enter a contact name (2-100 characters).")
    private String fullName;

    @NotBlank(message = "Please enter a valid email address.")
    @Email(message = "Please enter a valid email address.")
    @Size(max = 254, message = "Please enter a valid email address.")
    private String email;

    @NotBlank(message = "Please enter your company name (2-120 characters).")
    @Size(min = 2, max = 120, message = "Please enter your company name (2-120 characters).")
    private String companyName;

    // Optional: StringTrimmerEditor turns a blank submission into null (Section 7.3),
    // and @URL treats null as valid, so an empty field passes without a message.
    @URL(message = "Please enter a valid website address, for example https://example.com.")
    @Size(max = 200, message = "Please enter a valid website address, for example https://example.com.")
    private String companyWebsite;

    @Size(max = 1000, message = "Company description must be 1000 characters or fewer.")
    private String companyDescription;

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

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getCompanyWebsite() {
        return companyWebsite;
    }

    public void setCompanyWebsite(String companyWebsite) {
        this.companyWebsite = companyWebsite;
    }

    public String getCompanyDescription() {
        return companyDescription;
    }

    public void setCompanyDescription(String companyDescription) {
        this.companyDescription = companyDescription;
    }
}
