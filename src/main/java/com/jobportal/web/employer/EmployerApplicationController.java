package com.jobportal.web.employer;

import com.jobportal.domain.Interview;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.InterviewMode;
import com.jobportal.dto.ApplicationStatusOption;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.FileStorageService;
import com.jobportal.service.InterviewService;
import com.jobportal.service.JobApplicationService;
import com.jobportal.web.form.ApplicationStatusForm;
import com.jobportal.web.form.InternalNoteForm;
import com.jobportal.web.form.InterviewCancelForm;
import com.jobportal.web.form.InterviewForm;
import com.jobportal.web.support.Csv;
import com.jobportal.web.support.FileResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.springframework.web.util.UriComponentsBuilder;

// Employer application review (Section 6.3 E-F2/E-D2, 6.6 route summary). Thin
// controller (11.3 contract item 1): every rule, ownership check and side effect lives in
// JobApplicationService, this class only wires the form/PRG plumbing.
@Controller
public class EmployerApplicationController {

    // "dd MMM yyyy", matching every other date rendered by this feature (E-D2's own
    // table, JobApplicationService.HISTORY_DATE_FORMAT) - kept private to this class
    // since it is only ever used to format one export column, not shared like @fmt.
    private static final DateTimeFormatter EXPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final JobApplicationService jobApplicationService;
    private final InterviewService interviewService;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public EmployerApplicationController(JobApplicationService jobApplicationService,
            InterviewService interviewService, FileStorageService fileStorageService, Clock clock) {
        this.jobApplicationService = jobApplicationService;
        this.interviewService = interviewService;
        this.fileStorageService = fileStorageService;
        this.clock = clock;
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
        model.addAttribute("bulkStatusOptions", bulkStatusOptions());
        return "employer/applications";
    }

    // The bulk toolbar's "New status" dropdown (new feature): every status ANY current
    // status may legally move to, employer-assignable ones only - the union of
    // employerOptions() across all seven statuses, rather than a hand-picked list, so it
    // can never drift from the Section 5.6 matrix ApplicationStatusTest checks. WITHDRAWN
    // is excluded the same way it already is per-application (only the seeker may
    // withdraw), and APPLIED never appears here because no status's allowedNext() ever
    // includes it (5.6: nothing transitions "back" to Applied). Which of these a given
    // row can actually reach is decided per-row, not here - see
    // JobApplicationService.bulkChangeStatus.
    private List<ApplicationStatusOption> bulkStatusOptions() {
        Set<ApplicationStatus> union = EnumSet.noneOf(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            union.addAll(status.employerOptions());
        }
        List<ApplicationStatusOption> options = new ArrayList<>();
        for (ApplicationStatus status : union) {
            options.add(new ApplicationStatusOption(status.name(), status.getLabel()));
        }
        return options;
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

    // Bulk status change from the applications list (new feature). Reuses
    // JobApplicationService.bulkChangeStatus, which itself reuses changeStatus() row by
    // row - see that method's comment for why a mixed selection is applied where legal
    // and skipped (never silently) where it is not, rather than all-or-nothing. This
    // handler's own job is just turning that per-row result into one flash the employer
    // cannot misread, and sending them back to the exact filtered/paged list they were
    // looking at, from the three return* hidden fields the list page fills in with its
    // own current jobId/status/page (Section 7.9) - the POST-body equivalent of what
    // @pageLinks already does for that page's own GET links (web.support.PageLinks).
    //
    // "Simple POST button" as far as GlobalExceptionHandler is concerned (Section 7.2):
    // there is no per-field redisplay to do on this page, so an empty selection or a
    // missing status is answered with the same kind of flash-and-redirect as any legal
    // outcome, not a thrown BusinessRuleException.
    @PostMapping("/employer/applications/bulk-status")
    public String bulkChangeStatus(@RequestParam(required = false) List<Long> applicationIds,
            @RequestParam(required = false) ApplicationStatus newStatus, @RequestParam(required = false) Long returnJobId,
            @RequestParam(required = false) String returnStatus, @RequestParam(required = false) Integer returnPage,
            @AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        String target = listRedirect(returnJobId, returnStatus, returnPage);

        if (applicationIds == null || applicationIds.isEmpty()) {
            redirect.addFlashAttribute("error", "Select at least one application before applying a bulk status change.");
            return "redirect:" + target;
        }
        if (newStatus == null) {
            redirect.addFlashAttribute("error", "Choose a status to apply to the selected applications.");
            return "redirect:" + target;
        }

        JobApplicationService.BulkStatusResult result =
                jobApplicationService.bulkChangeStatus(applicationIds, me.getId(), newStatus);
        if (result.updated().isEmpty()) {
            redirect.addFlashAttribute("error", "No applications were changed to " + newStatus.getLabel()
                    + " - that change is not legal from any of the selected applications' current status. Skipped: "
                    + String.join("; ", result.skipped()) + ".");
        } else if (result.skipped().isEmpty()) {
            redirect.addFlashAttribute("success",
                    result.updated().size() + " application(s) updated to " + newStatus.getLabel() + ".");
        } else {
            redirect.addFlashAttribute("warning",
                    result.updated().size() + " application(s) updated to " + newStatus.getLabel() + ". "
                            + result.skipped().size() + " skipped (not a legal change from their current status): "
                            + String.join("; ", result.skipped()) + ".");
        }
        return "redirect:" + target;
    }

    // "/employer/applications" with whatever jobId/status/page the list page was showing
    // when the bulk form was submitted. Deliberately NOT SafeRedirects.backOrDashboard:
    // that reads the Referer header and falls back to /dashboard when it is missing or
    // cross-origin, which would lose the employer's filters exactly when they matter most
    // (a large filtered selection); this action has exactly one calling page and that
    // page always knows its own current filters already (they are already in its model as
    // "jobId"/"status", plus the Page's own "number"), so there is no reason to trade that
    // precision for a generic "go back" guess.
    private String listRedirect(Long returnJobId, String returnStatus, Integer returnPage) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/employer/applications");
        if (returnJobId != null) {
            url.queryParam("jobId", returnJobId);
        }
        if (returnStatus != null && !returnStatus.isBlank()) {
            url.queryParam("status", returnStatus);
        }
        if (returnPage != null) {
            url.queryParam("page", returnPage);
        }
        return url.build().toUriString();
    }

    // CSV export of the current filtered list (new feature). A plain GET - reading and
    // downloading a file changes nothing, so, exactly like the resume download below, it
    // needs no CSRF token and is a plain link rather than a form. jobId/status are read
    // the same raw, optional way list() reads them (Section 7.9) and resolved through the
    // exact same ownJobIdOrNull/normaliseStatus rules inside exportForEmployer, so the
    // file can never show a jobId or status this employer's own list() would have
    // refused. "page" is deliberately not accepted here: the file always holds the WHOLE
    // filtered list, never just the one page currently on screen (see exportForEmployer).
    //
    // Every field is written through Csv.field/row, which both quotes per RFC 4180 and
    // neutralises CSV injection (a candidate's own full name is attacker-controlled text
    // that ends up in a file the employer opens in Excel) - see web.support.Csv.
    @GetMapping("/employer/applications/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String jobId,
            @RequestParam(required = false) String status, @AuthenticationPrincipal AppUserDetails me) {
        List<JobApplication> applications = jobApplicationService.exportForEmployer(me.getId(), jobId, status);

        StringBuilder csv = new StringBuilder();
        csv.append(Csv.row("Reference", "Candidate", "Email", "Job", "Applied on", "Status"));
        for (JobApplication application : applications) {
            csv.append(Csv.row(application.getReference(), application.getSeeker().getFullName(),
                    application.getSeeker().getEmail(), application.getJob().getTitle(),
                    EXPORT_DATE_FORMAT.format(application.getAppliedAt()), application.getStatus().getLabel()));
        }
        String filename = "applications-" + LocalDate.now(clock) + ".csv";
        return FileResponses.csv(csv.toString(), filename);
    }

    // ==================== Interview scheduling ====================
    //
    // Three POSTs, all onto the same detail page, all thin in exactly the way the rest of
    // this class is (11.3 contract item 1): every rule - the application must already be at
    // the Interview stage, the slot must be in the future and not absurdly far in it, an
    // interview must exist and still be scheduled before it can be moved or called off -
    // lives in InterviewService, and none of them can move an application's status. The
    // employer reaches the Interview stage through the "Change status" form above, and
    // nowhere else.
    //
    // The split between what re-renders and what redirects is the same one the two handlers
    // above already make (Section 7.2/7.3): a FIELD the employer can fix (a missing date, a
    // video interview with no joining link) re-renders this page with the error attached to
    // it, while a BusinessRuleException - which by definition has no field to attach to,
    // since it means the page they were looking at is stale - is left uncaught for
    // GlobalExceptionHandler to flash and redirect.

    @PostMapping("/employer/applications/{id}/interview")
    public String scheduleInterview(@PathVariable Long id,
            @Valid @ModelAttribute("interviewForm") InterviewForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addDetailModel(id, me.getId(), model);
            return "employer/application-detail";
        }
        Interview interview = interviewService.schedule(id, me.getId(), form);
        redirect.addFlashAttribute("success", "Interview scheduled for " + interview.getWhenText()
                + ". The candidate has been notified.");
        return "redirect:/employer/applications/" + id;
    }

    // "Reschedule" is what the button says, but the service decides from the data whether
    // the slot actually moved (see InterviewService#reschedule) - so this handler words its
    // own flash the same way, from what came back, rather than promising a change the
    // employer may not have made.
    @PostMapping("/employer/applications/{id}/interview/reschedule")
    public String rescheduleInterview(@PathVariable Long id,
            @Valid @ModelAttribute("interviewForm") InterviewForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addDetailModel(id, me.getId(), model);
            return "employer/application-detail";
        }
        Interview interview = interviewService.reschedule(id, me.getId(), form);
        redirect.addFlashAttribute("success", "Interview updated - now " + interview.getWhenText()
                + ". The candidate has been notified.");
        return "redirect:/employer/applications/" + id;
    }

    @PostMapping("/employer/applications/{id}/interview/cancel")
    public String cancelInterview(@PathVariable Long id,
            @Valid @ModelAttribute("interviewCancelForm") InterviewCancelForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addDetailModel(id, me.getId(), model);
            return "employer/application-detail";
        }
        interviewService.cancel(id, me.getId(), form);
        redirect.addFlashAttribute("success", "Interview cancelled. The candidate has been notified.");
        return "redirect:/employer/applications/" + id;
    }

    // The form pre-filled with whatever is currently arranged, so "Reschedule" opens on the
    // existing details and the employer changes only what they mean to change - a blank
    // form would make every reschedule a re-typing exercise and every re-typing a chance to
    // lose the joining link. A cancelled interview is deliberately NOT pre-filled: the next
    // action on it is scheduling a NEW interview (InterviewService#schedule), and carrying
    // the called-off slot forward as a default is how a cancelled time gets accidentally
    // re-sent to the candidate.
    private InterviewForm interviewFormFor(Interview interview) {
        InterviewForm form = new InterviewForm();
        if (interview != null && !interview.isCancelled()) {
            form.setDate(interview.getScheduledAt().toLocalDate());
            form.setTime(interview.getScheduledAt().toLocalTime());
            form.setMode(interview.getMode());
            form.setLocation(interview.getLocation());
            form.setNotes(interview.getNotes());
        }
        return form;
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

        // Interview scheduling. "interview" is null when nothing has ever been arranged,
        // and non-null-but-cancelled once it has been called off - the template tells the
        // three live cases apart with @fmt.interviewState (see Formats), never by
        // re-deriving "is this in the future" itself. The two forms follow the same
        // "only if not already in the model" rule as the two above (Section 7.2): whichever
        // one was just submitted and failed validation is already there with its errors
        // attached, and must not be replaced by a fresh copy.
        Interview interview = interviewService.findForApplication(id).orElse(null);
        model.addAttribute("interview", interview);
        model.addAttribute("interviewModes", InterviewMode.values());
        if (!model.containsAttribute("interviewForm")) {
            model.addAttribute("interviewForm", interviewFormFor(interview));
        }
        if (!model.containsAttribute("interviewCancelForm")) {
            model.addAttribute("interviewCancelForm", new InterviewCancelForm());
        }
    }
}
