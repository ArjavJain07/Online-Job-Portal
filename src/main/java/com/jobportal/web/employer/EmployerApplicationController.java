package com.jobportal.web.employer;

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.dto.ApplicationStatusOption;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.FileStorageService;
import com.jobportal.service.JobApplicationService;
import com.jobportal.web.form.ApplicationStatusForm;
import com.jobportal.web.form.InternalNoteForm;
import com.jobportal.web.support.FileResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;

// Employer application review (Section 6.3 E-F2/E-D2, 6.6 route summary). Thin
// controller (11.3 contract item 1): every rule, ownership check and side effect lives in
// JobApplicationService, this class only wires the form/PRG plumbing.
@Controller
public class EmployerApplicationController {

    private final JobApplicationService jobApplicationService;
    private final FileStorageService fileStorageService;

    public EmployerApplicationController(JobApplicationService jobApplicationService,
            FileStorageService fileStorageService) {
        this.jobApplicationService = jobApplicationService;
        this.fileStorageService = fileStorageService;
    }

    // GET /employer/applications?jobId=&status=&page= (E-D2). jobId/status/page are left
    // as raw, optional strings and parsed by the service with a fallback (Section 7.9
    // binding rule), so a stray value in the URL never produces an error page.
    @GetMapping("/employer/applications")
    public String list(@RequestParam(required = false) String jobId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String page, @AuthenticationPrincipal AppUserDetails me, Model model) {
        JobApplicationService.ApplicationsQueue queue = jobApplicationService.listForEmployer(me.getId(), jobId, status, page);
        model.addAttribute("page", queue.applications());
        model.addAttribute("ownJobs", queue.ownJobs());
        model.addAttribute("jobId", queue.jobId());
        model.addAttribute("status", queue.status());
        model.addAttribute("statuses", ApplicationStatus.values());
        model.addAttribute("unreadCounts", queue.unreadCounts());
        return "employer/applications";
    }

    @GetMapping("/employer/applications/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        addDetailModel(id, me.getId(), model);
        return "employer/application-detail";
    }

    // AC-E-F2-1/AC-E-F2-2: a blank status or an over-length note re-renders this page
    // with the field error (Section 7.2); an otherwise valid but disallowed transition
    // (stale page or a hand-crafted POST, Section 6.3 E-F2 "Errors") is a plain
    // BusinessRuleException left uncaught, the same way AdminJobController#reject leaves
    // its own stale-status check uncaught - GlobalExceptionHandler turns it into an
    // "error" flash and redirects back to this same page (Section 7.3).
    @PostMapping("/employer/applications/{id}/status")
    public String changeStatus(@PathVariable Long id, @Valid @ModelAttribute("statusForm") ApplicationStatusForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addDetailModel(id, me.getId(), model);
            return "employer/application-detail";
        }
        JobApplication application = jobApplicationService.changeStatus(id, me.getId(), form);
        redirect.addFlashAttribute("success", "Status for " + application.getSeeker().getFullName() + " updated to "
                + application.getStatus().getLabel() + ".");
        return "redirect:/employer/applications/" + id;
    }

    @PostMapping("/employer/applications/{id}/internal-note")
    public String saveInternalNote(@PathVariable Long id, @Valid @ModelAttribute("noteForm") InternalNoteForm form,
            BindingResult result, @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addDetailModel(id, me.getId(), model);
            return "employer/application-detail";
        }
        jobApplicationService.saveInternalNote(id, me.getId(), form);
        redirect.addFlashAttribute("success", "Private note saved.");
        return "redirect:/employer/applications/" + id;
    }

    // Section 6.5.2/7.4: the copy submitted with THIS application, never the seeker's
    // current profile resume. Ownership is checked first and, if it fails, is left to
    // produce the ordinary 404 page; only a file missing from disk (the row is fine, the
    // bytes are not) gets the special flash-and-redirect treatment Section 7.4 asks for
    // instead of a 500 - see redirectWithFlash for why that needs building by hand here.
    @GetMapping("/employer/applications/{id}/resume")
    public ResponseEntity<Resource> resume(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        JobApplication application = jobApplicationService.getForEmployer(id, me.getId());
        try {
            Resource resource = fileStorageService.load(application.getResumeStoredName());
            return FileResponses.serve(resource, application.getResumeOriginalName(), application.getResumeContentType());
        } catch (ResourceNotFoundException e) {
            redirectWithFlash(request, response, "/employer/applications/" + id, e.getMessage());
            return null;
        }
    }

    // This route returns a file, not a view name, so RedirectAttributes (which only
    // works through a "redirect:" view name) is not available here - the redirect and its
    // flash message are instead built with the same FlashMap/FlashMapManager Spring's own
    // "redirect:" views use internally, and returning null after writing the response
    // tells Spring the request is already fully handled.
    private void redirectWithFlash(HttpServletRequest request, HttpServletResponse response, String targetPath,
            String message) throws IOException {
        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        flashMap.put("error", message);
        flashMap.setTargetRequestPath(request.getContextPath() + targetPath);
        RequestContextUtils.getFlashMapManager(request).saveOutputFlashMap(flashMap, request, response);
        response.sendRedirect(request.getContextPath() + targetPath);
    }

    // Everything employer/application-detail.html needs besides the two forms, plus a
    // freshly-built default for whichever of the two forms was not just submitted (so
    // the GET view and the "other" form on a failed POST always have something to bind
    // to) - the one that WAS just submitted is already in the model from
    // @ModelAttribute/BindingResult and is left untouched here (Section 7.2).
    private void addDetailModel(Long id, Long employerId, Model model) {
        JobApplication application = jobApplicationService.getForEmployer(id, employerId);
        // Named "jobApplication", never "application": Thymeleaf reserves the name
        // "application" for the servlet context attributes, so a model attribute called
        // "application" is shadowed and every ${application.x} silently reads as null.
        model.addAttribute("jobApplication", application);
        model.addAttribute("candidate", jobApplicationService.candidateProfile(application));
        model.addAttribute("applicationStatusChanges", jobApplicationService.timeline(id));

        // The statuses this application may move to next (Section 5.6), built here so the
        // template only loops over ready-made options.
        List<ApplicationStatusOption> statusOptions = new ArrayList<>();
        for (ApplicationStatus option : application.getStatus().employerOptions()) {
            statusOptions.add(new ApplicationStatusOption(option.name(), option.getLabel()));
        }
        model.addAttribute("statusOptions", statusOptions);

        if (!model.containsAttribute("statusForm")) {
            model.addAttribute("statusForm", new ApplicationStatusForm());
        }
        if (!model.containsAttribute("noteForm")) {
            InternalNoteForm noteForm = new InternalNoteForm();
            noteForm.setInternalNote(application.getInternalNote());
            model.addAttribute("noteForm", noteForm);
        }
    }
}
