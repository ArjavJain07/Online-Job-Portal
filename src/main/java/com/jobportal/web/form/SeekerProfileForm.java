package com.jobportal.web.form;

import com.jobportal.domain.enums.JobType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

// Seeker profile form (Section 6.4 S-F4/S-D3, field table). fullName and email live on
// User; every other field lives on SeekerProfile (Section 5.2) - SeekerProfileService
// splits them back apart when saving. GlobalModelAttributes#initBinder trims every String
// and turns a blank one into null before it reaches these annotations (Section 7.3), so
// every "optional" field below needs no separate blank check.
//
// The plan gives an exact message for fullName, email (duplicate), phone, skills and
// experienceYears; it is silent on the wording for the plain @Size limits of location,
// headline, education and about, so those four messages are this class's own reasonable
// choice (documented in the class-owning agent's summary), matching the tone of the ones
// the plan does give.
public class SeekerProfileForm {

    private static final String SKILLS_MESSAGE = "Skills must be at most 30 comma-separated items.";

    @NotBlank(message = "Please enter your full name (2-100 characters).")
    @Size(min = 2, max = 100, message = "Please enter your full name (2-100 characters).")
    private String fullName;

    // The duplicate-email message ("An account with this email already exists.") is
    // attached by the controller with result.rejectValue, the same pattern
    // EmployerProfileController uses (Section 7.2) - it needs a database lookup this
    // annotation alone cannot do.
    @NotBlank(message = "Please enter a valid email address.")
    @Email(message = "Please enter a valid email address.")
    @Size(max = 254, message = "Please enter a valid email address.")
    private String email;

    @Pattern(regexp = "^[0-9+\\- ]{10,15}$", message = "Phone must be 10-15 digits (spaces, + and - allowed).")
    private String phone;

    @Size(max = 100, message = "Location must be at most 100 characters.")
    private String location;

    @Size(max = 120, message = "Headline must be at most 120 characters.")
    private String headline;

    // Raw, as typed; SkillParser.parse() normalises it before it is saved (Section 7.8),
    // the same split between form limit and stored (normalised) length JobForm.skills uses.
    @Size(max = 300, message = SKILLS_MESSAGE)
    private String skills;

    @NotNull(message = "Experience must be 0-50 years.")
    @Min(value = 0, message = "Experience must be 0-50 years.")
    @Max(value = 50, message = "Experience must be 0-50 years.")
    private Integer experienceYears = 0;

    private JobType preferredJobType;

    @Size(max = 200, message = "Education must be at most 200 characters.")
    private String education;

    @Size(max = 1000, message = "About must be at most 1000 characters.")
    private String about;

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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public Integer getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(Integer experienceYears) {
        this.experienceYears = experienceYears;
    }

    public JobType getPreferredJobType() {
        return preferredJobType;
    }

    public void setPreferredJobType(JobType preferredJobType) {
        this.preferredJobType = preferredJobType;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }

    // At most 30 comma-separated items after the same trim/case-insensitive-dedupe rule
    // SkillParser applies before saving (Section 6.4 S-F4 field table); a blank value
    // passes here since skills is optional, unlike JobForm.isSkillsValid().
    @AssertTrue(message = SKILLS_MESSAGE)
    public boolean isSkillsValid() {
        if (skills == null || skills.isBlank()) {
            return true;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String part : skills.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                distinct.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return distinct.size() <= 30;
    }
}
