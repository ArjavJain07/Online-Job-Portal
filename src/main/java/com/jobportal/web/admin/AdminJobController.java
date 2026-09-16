package com.jobportal.web.admin;

import com.jobportal.domain.Job;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobModerationService;
import com.jobportal.web.form.JobReviewForm;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
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

// Admin job listing management (Section 6.2 A-F2/A-D2, 6.6 route summary). Thin
// controller: every rule and side effect lives in JobModerationService, this class only
// wires the form/PRG plumbing and reloads the review page when a decision is refused or
// fails validation (Section 7.2: "failed validation re-renders the same template").
@Controller
public class AdminJobController {

    private final JobModerationService jobModerationService;
    private final Clock clock;

    public AdminJobController(JobModerationService jobModerationService, Clock clock) {
        this.jobModerationService = jobModerationService;
        this.clock = clock;
    }

    // GET /admin/jobs?status=&q=&page= (A-D2). status/q/page are raw, optional strings
    // parsed by the service with a fallback (Section 7.9 binding rule): an unknown status
    // falls back to the pending tab, ALL means no status filter.
    @GetMapping("/admin/jobs")
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) String page, Model model) {
        JobModerationService.JobQueue queue = jobModerationService.queue(status, q, page);
        model.addAttribute("page", queue.jobs());
        model.addAttribute("activeTab", queue.activeTab());
        model.addAttribute("counts", queue.counts());
        model.addAttribute("applicationCounts", queue.applicationCounts());
        model.addAttribute("q", q);
        model.addAttribute("today", LocalDate.now(clock));
        return "admin/jobs";
    }

    @GetMapping("/admin/jobs/{id}")
    public String review(@PathVariable Long id, Model model) {
        addReviewModel(id, model);
        if (!model.containsAttribute("jobReviewForm")) {
            model.addAttribute("jobReviewForm", new JobReviewForm());
        }
        return "admin/job-review";
    }

    // AC-A-F2-1/AC-A-F2-3: a stale page (already decided), a passed deadline or a
    // deactivated employer all arrive as a BusinessRuleException; this is a simple POST
    // button (Section 7.2), so it is left uncaught and GlobalExceptionHandler turns it
    // into an "error" flash back on /admin/jobs.
    @PostMapping("/admin/jobs/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {
        Job job = jobModerationService.approve(id, me.getId());
        redirect.addFlashAttribute("success", "Job '" + job.getTitle() + "' approved and is now live.");
        return "redirect:/admin/jobs";
    }

    // Reached both from the review page's own reject form and from the inline row form on
    // the list page (Section 6.2 A-D2). Only the reason failing validation re-renders the
    // review page (route table: "on a missing reason re-render the review page"); a stale
    // status (job already decided elsewhere) is a plain BusinessRuleException, left
    // uncaught like approve's, so GlobalExceptionHandler flashes it and redirects back to
    // wherever the form was submitted from (Section 7.3).
    @PostMapping("/admin/jobs/{id}/reject")
    public String reject(@PathVariable Long id, @Valid @ModelAttribute("jobReviewForm") JobReviewForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addReviewModel(id, model);
            return "admin/job-review";
        }
        Job job = jobModerationService.reject(id, form, me.getId());
        redirect.addFlashAttribute("success", "Job '" + job.getTitle() + "' rejected. The employer can see your reason.");
        return "redirect:/admin/jobs";
    }

    // AC-A-F2-3: only an APPROVED job can be taken down; a stale status bubbles the same
    // way reject's does. Redirects back to the same job's review page on success (route
    // table), unlike approve/reject which go to the list.
    @PostMapping("/admin/jobs/{id}/take-down")
    public String takeDown(@PathVariable Long id, @Valid @ModelAttribute("jobReviewForm") JobReviewForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addReviewModel(id, model);
            return "admin/job-review";
        }
        Job job = jobModerationService.takeDown(id, form, me.getId());
        redirect.addFlashAttribute("success", "Job '" + job.getTitle() + "' taken down. The employer can see your reason.");
        return "redirect:/admin/jobs/" + id;
    }

    private void addReviewModel(Long id, Model model) {
        Job job = jobModerationService.getForReview(id);
        model.addAttribute("job", job);
        model.addAttribute("jobStatusChanges", jobModerationService.timeline(id));
        model.addAttribute("today", LocalDate.now(clock));
    }
}
