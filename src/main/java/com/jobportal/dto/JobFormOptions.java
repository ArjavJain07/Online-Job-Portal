package com.jobportal.dto;

import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;

// Select-option lists for employer/job-form.html (Section 6.3 E-F1, 7.2 controller
// pattern example: "model.addAttribute("formOptions", jobFormOptions())"). Built once in
// EmployerJobController so the template only loops values it is already holding, instead
// of calling JobCategory.values() itself (Section 7.1 note on admin/users.html).
public record JobFormOptions(JobCategory[] categories, JobType[] jobTypes, WorkMode[] workModes) {
}
