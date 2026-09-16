package com.jobportal.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

// Employer registration (Section 6.1 P-3, rules in 4.3): the same name/email/password
// rules as RegisterSeekerForm, plus the company name and an optional website.
public class RegisterEmployerForm extends RegisterSeekerForm {

    @NotBlank(message = "Please enter your company name (2-120 characters).")
    @Size(min = 2, max = 120, message = "Please enter your company name (2-120 characters).")
    private String companyName;

    // Optional: StringTrimmerEditor turns a blank submission into null (Section 7.3),
    // and @URL treats null as valid, so an empty field passes without a message.
    @URL(message = "Please enter a valid website address, for example https://example.com.")
    @Size(max = 200, message = "Please enter a valid website address, for example https://example.com.")
    private String companyWebsite;

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
}
