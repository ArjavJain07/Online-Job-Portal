package com.jobportal.web.seeker;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.SavedJob;
import com.jobportal.dto.JobSearchResult;
import com.jobportal.dto.RecommendationResult;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.FileValidationException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobApplicationService;
import com.jobportal.service.JobSearchService;
import com.jobportal.service.RecommendationService;
import com.jobportal.service.SavedJobService;
import com.jobportal.web.form.ApplicationForm;
import com.jobportal.web.form.JobSearchCriteria;
import com.jobportal.web.support.SafeRedirects;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
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

// Seeker job search, applying, recommendations and saved jobs (Section 6.4 S-F1, S-D1,
// S-F2, S-D5, 6.6 route summary; Section 16 future-work item 5 for the save/unsave/list
// methods in the marked section below). Thin controller (11.3 contract item 1): every
// rule, ownership check and side effect lives in JobApplicationService/RecommendationService/
// SavedJobService (and, for the search itself, the already-built JobSearchService), this
// class only wires the form/PRG plumbing.
@Controller
public class SeekerJobController {

    // Section 6.4 S-D5, 7.8 step 5: the full recommendations page shows the top 20
    // (the dashboard's own "top 6" is SeekerDashboardController's limit instead).
    private static final int RECOMMENDATIONS_PAGE_LIMIT = 20;

    private final JobSearchService jobSearchService;
    private final JobApplicationService jobApplicationService;
    private final RecommendationService recommendationService;
    private final SavedJobService savedJobService;
    private final Clock clock;

    public SeekerJobController(JobSearchService jobSearchService, JobApplicationService jobApplicationService,
            RecommendationService recommendationService, SavedJobService savedJobService, Clock clock) {
        this.jobSearchService = jobSearchService;
        this.jobApplicationService = jobApplicationService;
        this.recommendationService = recommendationService;
        this.savedJobService = savedJobService;
        this.clock = clock;
    }

    // GET /seeker/jobs?... (S-F1, S-D1): the same search JobBrowseController runs for the
    // public /jobs page, with an "Applied" badge added per row for this seeker (Section
    // 6.4 S-F1 output: "any status, including withdrawn").
    // "job" (added for the two-pane layout, Section 7.1 core rule 7): same optional
    // parameter and fallback rule as JobBrowseController#search, picking which of this
    // page's own results opens in the right-hand ".jp-jobs-detail" pane.
    @GetMapping("/seeker/jobs")
    public String search(JobSearchCriteria criteria, @RequestParam(name = "job", required = false) Long jobId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        JobSearchResult result = jobSearchService.search(criteria);
        model.addAttribute("criteria", criteria);
        model.addAttribute("page", result.jobs());
        model.addAttribute("warning", firstWarningOrNull(result));
        model.addAttribute("rows", jobApplicationService.withAppliedFlags(result.jobs().getContent(), me.getId()));

        Job selectedJob = jobSearchService.selectForPane(result.jobs().getContent(), jobId);
        model.addAttribute("selectedJob", selectedJob);
        model.addAttribute("selectedJobId", selectedJob == null ? null : selectedJob.getId());
        model.addAttribute("existingApplication", selectedJob == null ? null
                : jobApplicationService.findExisting(selectedJob.getId(), me.getId()).orElse(null));
        // Save/unsave button state for the pane (Section 16 future-work item 5): every
        // viewer of this page is already a job seeker (SecurityConfig), so - unlike the
        // public job-detail page's "saved" attribute - this is never null.
        model.addAttribute("saved", selectedJob == null ? null
                : savedJobService.isSaved(me.getId(), selectedJob.getId()));
        return "seeker/jobs";
    }

    // GET /seeker/recommendations (S-D5, Section 7.8): the full list, top 20, or the
    // "Latest jobs" fallback when the profile has no skills and the seeker has never
    // applied - the template reads result.fallback() to choose the heading/prompt, the
    // same way SeekerDashboardController's own "top 6" card does.
    @GetMapping("/seeker/recommendations")
    public String recommendations(@AuthenticationPrincipal AppUserDetails me, Model model) {
        RecommendationResult result = recommendationService.recommend(me.getId(), RECOMMENDATIONS_PAGE_LIMIT);
        model.addAttribute("recommendations", result);
        return "seeker/recommendations";
    }

    // GET /seeker/jobs/{jobId}/apply (S-F2). "Check order" (business rule 2) runs before
    // the form is even shown: a seeker who already applied, or whose job is no longer
    // Live, never sees the apply form at all.
    @GetMapping("/seeker/jobs/{jobId}/apply")
    public String applyForm(@PathVariable Long jobId, @AuthenticationPrincipal AppUserDetails me, Model model,
            RedirectAttributes redirect) {
        Job job = jobApplicationService.getJob(jobId);
        String blocked = applyGuard(job, me.getId(), redirect);
        if (blocked != null) {
            return blocked;
        }
        addApplyModel(job, me.getId(), model);
        return "seeker/apply";
    }

    // POST /seeker/jobs/{jobId}/apply (S-F2, multipart). Same guard as the GET, then Bean
    // Validation, then the one field rule Bean Validation cannot see (resumeChoice=PROFILE
    // needs a profile resume), then the file rules JobApplicationService/FileStorageService
    // enforce (Section 6.4 S-F2 "Check order").
    @PostMapping("/seeker/jobs/{jobId}/apply")
    public String apply(@PathVariable Long jobId, @Valid @ModelAttribute("applicationForm") ApplicationForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, Model model,
            RedirectAttributes redirect) {
        Job job = jobApplicationService.getJob(jobId);
        String blocked = applyGuard(job, me.getId(), redirect);
        if (blocked != null) {
            return blocked;
        }

        if (form.getResumeChoice() == ApplicationForm.ResumeChoice.PROFILE
                && !jobApplicationService.hasProfileResume(me.getId())) {
            result.rejectValue("resumeChoice", "noProfileResume", ApplicationForm.RESUME_CHOICE_MESSAGE);
        }
        if (result.hasErrors()) {
            addApplyModel(job, me.getId(), model);
            return "seeker/apply";
        }

        try {
            JobApplication application = jobApplicationService.apply(job, me.getId(), form);
            return "redirect:/seeker/applications/" + application.getId() + "?submitted";
        } catch (FileValidationException e) {
            // AC-S-F2-3: shown as a resumeFile field error, and the chosen file must be
            // picked again (Section 6.4 S-F2 business rule 7 - the browser cannot keep it).
            result.rejectValue("resumeFile", "invalid", e.getMessage());
            addApplyModel(job, me.getId(), model);
            return "seeker/apply";
        } catch (BusinessRuleException e) {
            // The defence-in-depth backstop in JobApplicationService.apply() (a genuine
            // double-submit race): send the seeker back to the apply route, where the
            // guard above now finds the just-inserted application and redirects them to it.
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/seeker/jobs/" + jobId + "/apply";
        }
    }

    private String firstWarningOrNull(JobSearchResult result) {
        return result.warnings().isEmpty() ? null : result.warnings().get(0);
    }

    // Section 6.4 S-F2 "Check order, the same on GET and POST": (a) the duplicate check,
    // then (b) the Live check, both ahead of anything else. Returns the redirect view name
    // to use when blocked, or null to let the caller continue.
    private String applyGuard(Job job, Long seekerId, RedirectAttributes redirect) {
        Optional<JobApplication> existing = jobApplicationService.findExisting(job.getId(), seekerId);
        if (existing.isPresent()) {
            redirect.addFlashAttribute("error", JobApplicationService.ALREADY_APPLIED_MESSAGE);
            return "redirect:/seeker/applications/" + existing.get().getId();
        }
        if (!job.isLive(LocalDate.now(clock))) {
            redirect.addFlashAttribute("error", JobApplicationService.NOT_LIVE_MESSAGE);
            return "redirect:/seeker/jobs";
        }
        return null;
    }

    // Everything seeker/apply.html needs besides the form itself: the job summary header
    // and the seeker's resume options (Section 6.4 S-F2 screen). The form is left alone
    // when one was already bound and is being re-shown after a validation error (Section
    // 7.2) - only a fresh GET builds a new one, defaulting "save to profile" to ticked
    // when the seeker has no profile resume yet (Section 6.4 S-F2 screen).
    private void addApplyModel(Job job, Long seekerId, Model model) {
        model.addAttribute("job", job);
        model.addAttribute("resumeOptions", jobApplicationService.resumeOptions(seekerId));
        if (!model.containsAttribute("applicationForm")) {
            boolean hasProfileResume = jobApplicationService.hasProfileResume(seekerId);
            ApplicationForm form = new ApplicationForm();
            // Sensible defaults for a fresh GET only (Section 6.4 S-F2 screen: saveToProfile
            // "ticked by default when the profile has no resume"): pre-selecting whichever
            // resume choice is actually usable also makes forms.js's on-load toggle show the
            // right section immediately, instead of leaving both radios unchecked.
            form.setResumeChoice(hasProfileResume ? ApplicationForm.ResumeChoice.PROFILE : ApplicationForm.ResumeChoice.UPLOAD);
            form.setSaveToProfile(!hasProfileResume);
            model.addAttribute("applicationForm", form);
        }
    }

    // ---- Saved jobs (Section 16 future-work item 5) ----
    // Added in its own marked section, the same convention 11.3 contract item 11 already
    // asks for on JobApplicationService, so a later change here never has to untangle
    // itself from the search/apply/recommendations methods above.

    // GET /seeker/saved-jobs: the seeker's own bookmarked jobs, newest first, paginated
    // like every other list of more than a handful (Section 7.9's pageSize setting) -
    // mirrors SeekerApplicationController#history's page+rows shape.
    @GetMapping("/seeker/saved-jobs")
    public String savedJobs(@RequestParam(required = false) String page, @AuthenticationPrincipal AppUserDetails me,
            Model model) {
        Page<SavedJob> savedJobs = savedJobService.list(me.getId(), page);
        model.addAttribute("page", savedJobs);
        return "seeker/saved-jobs";
    }

    // POST /seeker/jobs/{jobId}/save and .../unsave: simple POST buttons (Section 7.2),
    // reachable from the job list pane, the standalone job detail page and the saved-jobs
    // list itself. SafeRedirects.backOrDashboard sends the seeker straight back to
    // whichever of those pages the button was on, with every filter/page/selected-job query
    // parameter it already had - the task's own "without losing the user's place"
    // requirement, met by reusing the exact mechanism GlobalExceptionHandler and
    // EmployerJobController#reopen already use for the same "go back to where you were"
    // need, rather than inventing a bespoke return-url parameter for this one button.
    @PostMapping("/seeker/jobs/{jobId}/save")
    public String save(@PathVariable Long jobId, @AuthenticationPrincipal AppUserDetails me,
            HttpServletRequest request, RedirectAttributes redirect) {
        savedJobService.save(me.getId(), jobId);
        redirect.addFlashAttribute("success", "Job saved. Find it any time under My saved jobs.");
        return "redirect:" + SafeRedirects.backOrDashboard(request);
    }

    @PostMapping("/seeker/jobs/{jobId}/unsave")
    public String unsave(@PathVariable Long jobId, @AuthenticationPrincipal AppUserDetails me,
            HttpServletRequest request, RedirectAttributes redirect) {
        savedJobService.unsave(me.getId(), jobId);
        redirect.addFlashAttribute("success", "Job removed from your saved jobs.");
        return "redirect:" + SafeRedirects.backOrDashboard(request);
    }
}
