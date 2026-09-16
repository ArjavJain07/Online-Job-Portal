package com.jobportal.web.employer;

import com.jobportal.domain.JobApplication;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.MessageService;
import com.jobportal.web.form.MessageForm;
import jakarta.validation.Valid;
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

// Employer messaging (Section 6.5.1, E-F3/E-D3, 6.6 route summary). Thin controller (11.3
// contract item 1): ownership, the blocking rules and the read-receipt update all live in
// MessageService; this class only wires the form/PRG plumbing, the same shape
// EmployerApplicationController uses for the application detail page's own two forms.
@Controller
public class EmployerMessageController {

    private final MessageService messageService;

    public EmployerMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    // GET /employer/messages (E-D3 inbox).
    @GetMapping("/employer/messages")
    public String inbox(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("threads", messageService.inboxForEmployer(me.getId()));
        return "employer/messages";
    }

    // GET /employer/messages/new?applicationId= (Section 6.5.1 "Compose page"):
    // applicationId is a lenient, optional query parameter (Section 7.9) that only
    // pre-selects a row already in the select's own option list.
    @GetMapping("/employer/messages/new")
    public String composeForm(@RequestParam(required = false) String applicationId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        if (!model.containsAttribute("messageForm")) {
            MessageForm form = new MessageForm();
            form.setApplicationId(messageService.preselectedApplicationId(me.getId(), applicationId));
            model.addAttribute("messageForm", form);
        }
        model.addAttribute("applications", messageService.composableApplicationsForEmployer(me.getId()));
        return "employer/message-compose";
    }

    // POST /employer/messages -> redirect to the new thread (Section 6.5.1 route table).
    // A blank body re-renders this page with the field error; an empty/foreign
    // applicationId fails Spring's own binding first (Section 7.3, MessageForm's own
    // class comment) and is caught by the same result.hasErrors() branch.
    @PostMapping("/employer/messages")
    public String compose(@Valid @ModelAttribute("messageForm") MessageForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("applications", messageService.composableApplicationsForEmployer(me.getId()));
            return "employer/message-compose";
        }
        JobApplication application = messageService.sendFromEmployer(form.getApplicationId(), me.getId(), form);
        redirect.addFlashAttribute("success", "Message sent to " + application.getSeeker().getFullName() + ".");
        return "redirect:/employer/messages/" + application.getId();
    }

    // GET /employer/messages/{applicationId} (Section 6.5.1 thread page). One of the four
    // pages that marks a thread read, so addThreadModel also overwrites
    // "unreadMessageCount" for this same response's navbar badge (Section 6.5.1 "Badge on
    // a page that marks messages read").
    @GetMapping("/employer/messages/{applicationId}")
    public String thread(@PathVariable Long applicationId, @AuthenticationPrincipal AppUserDetails me, Model model) {
        addThreadModel(applicationId, me.getId(), model);
        return "employer/message-thread";
    }

    // POST /employer/messages/{applicationId} -> redirect to the thread, or to the
    // application detail page when ?from=application (Section 6.5.1: only that exact
    // value redirects there, anything else redirects to the thread, Section 7.9). A
    // blocked thread (stale page or a hand-crafted request) throws BusinessRuleException
    // from the service and is left uncaught here, the same pattern
    // EmployerApplicationController#changeStatus follows for its own refused transitions.
    @PostMapping("/employer/messages/{applicationId}")
    public String send(@PathVariable Long applicationId, @Valid @ModelAttribute("messageForm") MessageForm form,
            BindingResult result, @RequestParam(required = false) String from, @AuthenticationPrincipal AppUserDetails me,
            Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addThreadModel(applicationId, me.getId(), model);
            return "employer/message-thread";
        }
        JobApplication application = messageService.sendFromEmployer(applicationId, me.getId(), form);
        redirect.addFlashAttribute("success", "Message sent to " + application.getSeeker().getFullName() + ".");
        return "application".equals(from)
                ? "redirect:/employer/applications/" + applicationId
                : "redirect:/employer/messages/" + applicationId;
    }

    // Everything employer/message-thread.html needs besides a fresh "messageForm" (left
    // untouched when one already carries a field error from a failed POST, Section 7.2).
    private void addThreadModel(Long applicationId, Long employerId, Model model) {
        MessageService.ThreadView view = messageService.openThreadForEmployer(applicationId, employerId);
        model.addAttribute("jobApplication", view.application());
        model.addAttribute("messages", view.messages());
        model.addAttribute("blockedReason", view.blockedReason());
        model.addAttribute("threads", messageService.inboxForEmployer(employerId));
        model.addAttribute("unreadMessageCount", messageService.unreadCount(employerId));
        if (!model.containsAttribute("messageForm")) {
            model.addAttribute("messageForm", new MessageForm());
        }
    }
}
