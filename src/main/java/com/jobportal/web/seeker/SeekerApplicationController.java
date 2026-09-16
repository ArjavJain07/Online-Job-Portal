package com.jobportal.web.seeker;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobDisplayStatus;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.FileStorageService;
import com.jobportal.service.JobApplicationService;
import com.jobportal.web.support.FileResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;

// Seeker application tracking, history and resume download (Section 6.4 S-F3+S-D2, S-D4,
// Section 6.5.2, 6.6 route summary). Thin controller (11.3 contract item 1): every rule,
// ownership check and side effect this class needs lives in JobApplicationService (its
// seeker-side section, 11.3 contract item 11), the exact counterpart of
// EmployerApplicationController on the employer side.
@Controller
public class SeekerApplicationController {

    private final JobApplicationService jobApplicationService;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public SeekerApplicationController(JobApplicationService jobApplicationService, FileStorageService fileStorageService,
            Clock clock) {
        this.jobApplicationService = jobApplicationService;
        this.fileStorageService = fileStorageService;
        this.clock = clock;
    }

    // GET /seeker/applications?status= (S-D2). status is left as a raw, optional string
    // and parsed by the service with a fallback to ALL (Section 7.9 binding rule).
    @GetMapping("/seeker/applications")
    public String active(@RequestParam(required = false) String status, @AuthenticationPrincipal AppUserDetails me,
            Model model) {
        var view = jobApplicationService.activeApplicationsForSeeker(me.getId(), status);
        model.addAttribute("applications", view.applications());
        model.addAttribute("status", view.status());
        model.addAttribute("totalActive", view.totalActive());
        model.addAttribute("appliedCount", view.appliedCount());
        model.addAttribute("underReviewCount", view.underReviewCount());
        model.addAttribute("shortlistedCount", view.shortlistedCount());
        model.addAttribute("interviewCount", view.interviewCount());
        model.addAttribute("unreadCounts", view.unreadCounts());
        return "seeker/applications";
    }

    // GET /seeker/applications/history?result=&view=&page= (S-D4). Same lenient binding
    // as above for result/view/page.
    @GetMapping("/seeker/applications/history")
    public String history(@RequestParam(required = false) String result, @RequestParam(required = false) String view,
            @RequestParam(required = false) String page, @AuthenticationPrincipal AppUserDetails me, Model model) {
        var history = jobApplicationService.applicationHistoryForSeeker(me.getId(), result, view, page);
        model.addAttribute("page", history.page());
        model.addAttribute("rows", history.rows());
        model.addAttribute("view", history.view());
        model.addAttribute("result", history.result());
        model.addAttribute("summary", history.summary());
        return "seeker/application-history";
    }

    // GET /seeker/applications/{id} (S-F3). ?submitted (present only right after the S-F2
    // apply redirect) is read straight from ${param.submitted} in the template, the same
    // way auth/login.html reads its own query-parameter flags (Section 7.1: ${param.*} is
    // Thymeleaf's own built-in object, safe to use directly - only a MODEL ATTRIBUTE
    // literally named "param" would be shadowed).
    @GetMapping("/seeker/applications/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        JobApplication application = jobApplicationService.viewApplicationForSeeker(id, me.getId());
        // Named "jobApplication", never "application" (Section 7.1 "Reserved model
        // attribute names" / 11.3 contract item 9): Thymeleaf reserves "application" for
        // the servlet context attribute map, so a model attribute called "application" is
        // shadowed and every ${application.x} in the template would silently read null.
        model.addAttribute("jobApplication", application);
        model.addAttribute("applicationStatusChanges", jobApplicationService.timeline(id));
        model.addAttribute("trackerStepIndex", trackerStepIndex(application.getStatus()));
        model.addAttribute("notLiveMessage", notLiveMessage(application.getJob()));
        return "seeker/application-detail";
    }

    // POST /seeker/applications/{id}/withdraw (S-F3). A simple POST button
    // (data-confirm): BusinessRuleException is deliberately left uncaught, the same
    // pattern every other simple status-change button in the app follows (Section 7.2,
    // 11.3's own text names "withdraw" among these buttons) - GlobalExceptionHandler
    // turns a stale/already-final application into the "error" flash and redirects back
    // to the page the button was on.
    @PostMapping("/seeker/applications/{id}/withdraw")
    public String withdraw(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        JobApplication application = jobApplicationService.withdraw(id, me.getId());
        redirect.addFlashAttribute("success",
                "Your application for '" + application.getJob().getTitle() + "' has been withdrawn.");
        return "redirect:/seeker/applications";
    }

    // GET /seeker/applications/{id}/resume (Section 6.5.2): the copy submitted WITH this
    // application, never the current profile resume (Section 7.4 "Copies", I-14). Plain
    // ownership check (getForSeeker, no side effects) - opening the detail page is what
    // marks the application viewed, not downloading its resume. Missing bytes on disk get
    // the flash-and-redirect Section 7.4 asks for instead of a 500, the same split
    // EmployerApplicationController#resume uses for the employer's copy of the same file.
    @GetMapping("/seeker/applications/{id}/resume")
    public ResponseEntity<Resource> resume(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        JobApplication application = jobApplicationService.getForSeeker(id, me.getId());
        try {
            Resource resource = fileStorageService.load(application.getResumeStoredName());
            return FileResponses.serve(resource, application.getResumeOriginalName(), application.getResumeContentType());
        } catch (ResourceNotFoundException e) {
            redirectWithFlash(request, response, "/seeker/applications/" + id, e.getMessage());
            return null;
        }
    }

    // Same hand-built redirect-with-flash EmployerApplicationController#resume uses: this
    // route returns a file, not a view name, so RedirectAttributes is not available.
    private void redirectWithFlash(HttpServletRequest request, HttpServletResponse response, String targetPath,
            String message) throws IOException {
        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        flashMap.put("error", message);
        flashMap.setTargetRequestPath(request.getContextPath() + targetPath);
        RequestContextUtils.getFlashMapManager(request).saveOutputFlashMap(flashMap, request, response);
        response.sendRedirect(request.getContextPath() + targetPath);
    }

    // The "not Live" note on the detail page (Section 6.4 S-F3 "Detail page" screen),
    // built here rather than computed in the template: Job.displayStatus(today) is a bean
    // method call that needs T(java.time.LocalDate).now() as an argument, and Thymeleaf
    // 3.1 can reject exactly that combination in some attribute positions (the same
    // reasoning PageLinks.build(Page) documents) - simplest to just hand the template a
    // ready-made string, or null when the job is Live and no note is shown.
    private String notLiveMessage(Job job) {
        JobDisplayStatus displayStatus = job.displayStatus(LocalDate.now(clock));
        if (displayStatus == JobDisplayStatus.LIVE) {
            return null;
        }
        if (displayStatus == JobDisplayStatus.PENDING_APPROVAL) {
            return "This job is being updated and is temporarily hidden.";
        }
        return "This job is no longer open, but your application is still being processed.";
    }

    // Position (0-4) of the current status on the Applied-Under review-Shortlisted-
    // Interview-Hired tracker (Section 6.4 S-F3 "Status tracker"), or null for the two
    // final states the tracker shows as a badge instead (Rejected/Withdrawn). Built here,
    // not on the entity or the enum, since it is presentation for this one page only.
    private Integer trackerStepIndex(ApplicationStatus status) {
        return switch (status) {
            case APPLIED -> 0;
            case UNDER_REVIEW -> 1;
            case SHORTLISTED -> 2;
            case INTERVIEW -> 3;
            case HIRED -> 4;
            case REJECTED, WITHDRAWN -> null;
        };
    }
}
