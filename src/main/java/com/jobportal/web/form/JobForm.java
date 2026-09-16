package com.jobportal.web.form;

import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

// Employer job posting form (Section 6.3 E-F1/E-D1, field table). One class handles both
// GET /employer/jobs/new and GET /employer/jobs/{id}/edit; JobService.create/update do the
// checks that need the database or "today" from the Clock (active-job limit, the deadline
// range, re-approval) and throw BusinessRuleException for those (Section 7.3), since Bean
// Validation alone cannot see either one.
public class JobForm {

    private static final String SALARY_MESSAGE = "Salary must be a whole number from 0 to 10,00,00,000.";
    private static final String SKILLS_MESSAGE = "Add at least one skill (at most 30).";
    private static final String EXPERIENCE_MESSAGE = "Experience must be 0-30 years.";
    private static final String OPENINGS_MESSAGE = "Openings must be 1-1000.";
    private static final String DEADLINE_MESSAGE = "Deadline must be between today and 180 days from now.";

    @NotBlank(message = "Title must be 3-120 characters.")
    @Size(min = 3, max = 120, message = "Title must be 3-120 characters.")
    private String title;

    @NotBlank(message = "Description must be 30-4000 characters.")
    @Size(min = 30, max = 4000, message = "Description must be 30-4000 characters.")
    private String description;

    @NotBlank(message = "Requirements must be 10-2000 characters.")
    @Size(min = 10, max = 2000, message = "Requirements must be 10-2000 characters.")
    private String requirements;

    @NotBlank(message = SKILLS_MESSAGE)
    @Size(max = 300, message = SKILLS_MESSAGE)
    private String skills;

    @NotNull(message = "Please choose a category.")
    private JobCategory category;

    @NotNull(message = "Please choose a job type.")
    private JobType jobType;

    @NotNull(message = "Please choose a work mode.")
    private WorkMode workMode;

    @NotBlank(message = "Please enter a location (at most 100 characters).")
    @Size(max = 100, message = "Please enter a location (at most 100 characters).")
    private String location;

    @NotNull(message = SALARY_MESSAGE)
    @Min(value = 0, message = SALARY_MESSAGE)
    @Max(value = 100_000_000, message = SALARY_MESSAGE)
    private Integer salaryMin;

    @NotNull(message = SALARY_MESSAGE)
    @Min(value = 0, message = SALARY_MESSAGE)
    @Max(value = 100_000_000, message = SALARY_MESSAGE)
    private Integer salaryMax;

    @NotNull(message = EXPERIENCE_MESSAGE)
    @Min(value = 0, message = EXPERIENCE_MESSAGE)
    @Max(value = 30, message = EXPERIENCE_MESSAGE)
    private Integer minExperienceYears;

    @NotNull(message = OPENINGS_MESSAGE)
    @Min(value = 1, message = OPENINGS_MESSAGE)
    @Max(value = 1000, message = OPENINGS_MESSAGE)
    private Integer openings = 1;

    // The today-to-180-days range is checked in the service (needs the Clock, and on
    // edit only when the value actually changed) - see the class comment.
    @NotNull(message = DEADLINE_MESSAGE)
    private LocalDate applicationDeadline;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public JobCategory getCategory() {
        return category;
    }

    public void setCategory(JobCategory category) {
        this.category = category;
    }

    public JobType getJobType() {
        return jobType;
    }

    public void setJobType(JobType jobType) {
        this.jobType = jobType;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public void setWorkMode(WorkMode workMode) {
        this.workMode = workMode;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(Integer salaryMin) {
        this.salaryMin = salaryMin;
    }

    public Integer getSalaryMax() {
        return salaryMax;
    }

    public void setSalaryMax(Integer salaryMax) {
        this.salaryMax = salaryMax;
    }

    public Integer getMinExperienceYears() {
        return minExperienceYears;
    }

    public void setMinExperienceYears(Integer minExperienceYears) {
        this.minExperienceYears = minExperienceYears;
    }

    public Integer getOpenings() {
        return openings;
    }

    public void setOpenings(Integer openings) {
        this.openings = openings;
    }

    public LocalDate getApplicationDeadline() {
        return applicationDeadline;
    }

    public void setApplicationDeadline(LocalDate applicationDeadline) {
        this.applicationDeadline = applicationDeadline;
    }

    // Class-level rule shown on the salaryMax field (template tip, Section 6.3):
    // th:errors="*{salaryRangeValid}". Null values pass here so only the @NotNull/@Min/
    // @Max messages above show for a genuinely missing figure.
    @AssertTrue(message = "Maximum salary must be at least the minimum salary.")
    public boolean isSalaryRangeValid() {
        if (salaryMin == null || salaryMax == null) {
            return true;
        }
        return salaryMax >= salaryMin;
    }

    // 1 to 30 skills after the same trim/case-insensitive-dedupe rule SkillParser applies
    // before saving (Section 6.3 field table, 5.5 business rule 4). Duplicated here
    // (rather than calling SkillParser) only to count entries before the 30-skill cap it
    // applies while parsing; a blank value is left to @NotBlank above.
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
        return !distinct.isEmpty() && distinct.size() <= 30;
    }
}
