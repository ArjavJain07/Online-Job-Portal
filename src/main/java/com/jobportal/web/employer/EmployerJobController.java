package com.jobportal.web.employer;

import com.jobportal.domain.Job;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.JobFormOptions;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobService;
import com.jobportal.web.form.JobForm;
import com.jobportal.web.form.ReopenJobForm;
import com.jobportal.web.support.SafeRedirects;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Employer job posting and management (Section 6.3 E-F1/E-D1/E-D4, 6.6 route summary).
// Thin controller (11.3 contract item 1): every rule, transition and activity log entry
// lives in JobService, this class only wires the form/PRG plumbing. Literal paths
// ("/employer/jobs/new", "/employer/jobs/history") are matched before "{id}" by Spring
// MVC on their own (Section 6.6 note), so no special method ordering is needed here.
@Controller
public class EmployerJobController {

    private final JobService jobService;
    private final Clock clock;

    public EmployerJobController(JobService jobService, Clock clock) {
        this.jobService = jobService;
        this.clock = clock;
    }

    // GET /employer/jobs (E-D1): the employer's current jobs (pending, live/expired and
    // rejected - closed jobs are in history), not paginated (Section 6.0, bounded by
    // maxActiveJobsPerEmployer).
    @GetMapping("/employer/jobs")
    public String list(@AuthenticationPrincipal AppUserDetails me, Model model) {
        JobService.EmployerJobList result = jobService.employerJobs(me.getId());
        model.addAttribute("jobs", result.jobs());
        model.addAttribute("activeCount", result.activeCount());
        model.addAttribute("maxActiveJobs", result.maxActiveJobs());
        model.addAttribute("applicationCounts", result.applicationCounts());
        model.addAttribute("today", LocalDate.now(clock));
        return "employer/jobs";
    }

    @GetMapping("/employer/jobs/new")
    public String newForm(Model model) {
        if (!model.containsAttribute("jobForm")) {
            JobForm form = new JobForm();
            form.setApplicationDeadline(LocalDate.now(clock).plusDays(30));
            model.addAttribute("jobForm", form);
        }
        addFormModel(model, null);
        return "employer/job-form";
    }

    // AC-E-F1-1/AC-E-F1-2/AC-E-F1-3: invalid fields re-render this same template with
    // field errors (Section 7.2); the active-job limit and the deadline range come back
    // as a BusinessRuleException, shown as a global form error the same way (7.2 example).
    @PostMapping("/employer/jobs")
    public String create(@Valid @ModelAttribute("jobForm") JobForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addFormModel(model, null);
            return "employer/job-form";
        }
        try {
            Job job = jobService.create(me.getId(), form);
            redirect.addFlashAttribute("success", jobService.createdMessage(job));
            return "redirect:/employer/jobs/" + job.getId();
        } catch (BusinessRuleException e) {
            result.reject("businessRule", e.getMessage());
            addFormModel(model, null);
            return "employer/job-form";
        }
    }

    // GET /employer/jobs/history?status=&page= (E-D4). status/page are raw, optional
    // strings with a fallback in the service (Section 7.9 binding rule).
    @GetMapping("/employer/jobs/history")
    public String history(@RequestParam(required = false) String status, @RequestParam(required = false) String page,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        JobService.EmployerJobHistory history = jobService.employerJobHistory(me.getId(), status, page);
        model.addAttribute("page", history.page());
        model.addAttribute("rows", history.rows());
        model.addAttribute("activeTab", history.activeTab());
        model.addAttribute("summary", history.summary());
        model.addAttribute("today", LocalDate.now(clock));
        return "employer/job-history";
    }

    // GET /employer/jobs/{id} (E-D1 detail / E-D4 timeline): ownership-checked (404 for
    // another employer's job, Section 4.5); the timeline is passed exactly like the admin
    // review page's ("jobStatusChanges", oldest first) so both pages can share
    // fragments/timeline :: jobTimeline with the same model attribute name.
    @GetMapping("/employer/jobs/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        Job job = jobService.getOwned(me.getId(), id);
        Map<ApplicationStatus, Long> byStatus = jobService.applicationsByStatus(me.getId(), id);
        model.addAttribute("job", job);
        model.addAttribute("jobStatusChanges", jobService.timeline(id));
        model.addAttribute("applicationsByStatus", byStatus);
        model.addAttribute("totalApplications", jobService.totalApplications(byStatus));
        model.addAttribute("today", LocalDate.now(clock));
        return "employer/job-detail";
    }

    // A closed job has no Edit button in either template (5.5: "Closed jobs can't be
    // edited"), but a direct GET still needs to refuse it rather than show a form whose
    // POST would only fail.
    @GetMapping("/employer/jobs/{id}/edit")
    public String editForm(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        Job job = jobService.getOwned(me.getId(), id);
        if (job.getStatus() == JobStatus.CLOSED) {
            throw new BusinessRuleException(JobService.CLOSED_CANNOT_EDIT_MESSAGE);
        }
        if (!model.containsAttribute("jobForm")) {
            model.addAttribute("jobForm", toForm(job));
        }
        addFormModel(model, id);
        return "employer/job-form";
    }

    // AC-E-D1-2: which flash text applies (plain "updated", re-approval, or resubmitted)
    // depends on the job's previous status and whether content changed - JobService.update
    // works that out and hands back the matching message (Section 6.3 output table).
    @PostMapping("/employer/jobs/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("jobForm") JobForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addFormModel(model, id);
            return "employer/job-form";
        }
        try {
            JobService.JobActionResult outcome = jobService.update(me.getId(), id, form);
            redirect.addFlashAttribute("success", outcome.message());
            return "redirect:/employer/jobs/" + id;
        } catch (BusinessRuleException e) {
            result.reject("businessRule", e.getMessage());
            addFormModel(model, id);
            return "employer/job-form";
        }
    }

    // Simple POST button (Section 7.2): does not catch BusinessRuleException (job already
    // closed), so a stale page reaches GlobalExceptionHandler and comes back as an "error"
    // flash on the page the button was on.
    @PostMapping("/employer/jobs/{id}/close")
    public String close(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        Job job = jobService.close(me.getId(), id);
        redirect.addFlashAttribute("success", "Job '" + job.getTitle() + "' closed. It is no longer accepting applications.");
        return "redirect:/employer/jobs";
    }

    // Posted from a plain (unbound) date input on both employer/job-detail.html and
    // employer/job-history.html (Section 6.3 E-D4 "Reopen form"). A blank date fails
    // ReopenJobForm's @NotNull before the service ever sees it; that and the service's own
    // range/limit checks both end up as the same "error" flash back on whichever page the
    // form was submitted from, exactly like every other simple POST button here.
    @PostMapping("/employer/jobs/{id}/reopen")
    public String reopen(@PathVariable Long id, @Valid @ModelAttribute("reopenForm") ReopenJobForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, HttpServletRequest request,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("error", JobService.REOPEN_DEADLINE_RANGE_MESSAGE);
            return "redirect:" + SafeRedirects.backOrDashboard(request);
        }
        JobService.JobActionResult outcome = jobService.reopen(me.getId(), id, form);
        redirect.addFlashAttribute("success", outcome.message());
        return "redirect:/employer/jobs/" + id;
    }

    // Simple POST button: the "0 applications" rule is also enforced by disabling the
    // button in both templates (Section 6.3), so the refusal message is only reachable
    // from a stale page or a hand-crafted request (AC note in the plan) - left uncaught
    // like close()'s.
    @PostMapping("/employer/jobs/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        String title = jobService.delete(me.getId(), id);
        redirect.addFlashAttribute("success", "Job '" + title + "' deleted.");
        return "redirect:/employer/jobs";
    }

    private JobForm toForm(Job job) {
        JobForm form = new JobForm();
        form.setTitle(job.getTitle());
        form.setDescription(job.getDescription());
        form.setRequirements(job.getRequirements());
        // From the Skill relation, not the legacy CSV column (Section 10.8): the edit box
        // must be pre-filled with exactly the labels the page renders as chips, so an
        // employer who saves without touching the field changes nothing.
        form.setSkills(String.join(", ", job.skillList()));
        form.setCategory(job.getCategory());
        form.setJobType(job.getJobType());
        form.setWorkMode(job.getWorkMode());
        form.setLocation(job.getLocation());
        form.setSalaryMin(job.getSalaryMin());
        form.setSalaryMax(job.getSalaryMax());
        form.setMinExperienceYears(job.getMinExperienceYears());
        form.setOpenings(job.getOpenings());
        form.setApplicationDeadline(job.getApplicationDeadline());
        return form;
    }

    // Every model attribute employer/job-form.html needs besides "jobForm": the enum
    // option lists (Section 7.1 note: an enum can't be looped with T(...) in the template
    // itself), the id (null on create) and the computed form action/page title (built
    // here, not with a template-side ternary - same reasoning as AdminUserController).
    private void addFormModel(Model model, Long id) {
        model.addAttribute("formOptions", new JobFormOptions(JobCategory.values(), JobType.values(), WorkMode.values()));
        model.addAttribute("jobId", id);
        model.addAttribute("formAction", id == null ? "/employer/jobs" : "/employer/jobs/" + id);
        model.addAttribute("pageTitle", id == null ? "Post a job" : "Edit job");
    }
}
