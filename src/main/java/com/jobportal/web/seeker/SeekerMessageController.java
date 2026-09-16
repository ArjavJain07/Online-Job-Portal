package com.jobportal.web.seeker;

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

// Seeker messaging (Section 6.5.1, E-F3 seeker side, 6.6 route summary). The exact
// counterpart of EmployerMessageController on the seeker side, minus compose: the seeker
// never starts a thread (decision D-13), only replies once the employer has messaged them.
@Controller
public class SeekerMessageController {

    private final MessageService messageService;

    public SeekerMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    // GET /seeker/messages (Section 6.5.1 "Seeker inbox").
    @GetMapping("/seeker/messages")
    public String inbox(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("threads", messageService.inboxForSeeker(me.getId()));
        return "seeker/messages";
    }

    // GET /seeker/messages/{applicationId} (Section 6.5.1 thread page). One of the four
    // pages that marks a thread read, so addThreadModel also overwrites
    // "unreadMessageCount" for this same response's navbar badge.
    @GetMapping("/seeker/messages/{applicationId}")
    public String thread(@PathVariable Long applicationId, @AuthenticationPrincipal AppUserDetails me, Model model) {
        addThreadModel(applicationId, me.getId(), model);
        return "seeker/message-thread";
    }

    // POST /seeker/messages/{applicationId} -> redirect to the thread, or to the
    // application detail page when ?from=application (Section 6.5.1/7.9, same lenient
    // rule as the employer side). "You can reply once the employer has messaged you." (and
    // the withdrawn/deactivated sentences) reach the page as an "error" flash through
    // GlobalExceptionHandler, the same uncaught-BusinessRuleException pattern
    // EmployerMessageController#send follows.
    @PostMapping("/seeker/messages/{applicationId}")
    public String send(@PathVariable Long applicationId, @Valid @ModelAttribute("messageForm") MessageForm form,
            BindingResult result, @RequestParam(required = false) String from, @AuthenticationPrincipal AppUserDetails me,
            Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            addThreadModel(applicationId, me.getId(), model);
            return "seeker/message-thread";
        }
        JobApplication application = messageService.sendFromSeeker(applicationId, me.getId(), form);
        redirect.addFlashAttribute("success",
                "Message sent to " + application.getJob().getEmployer().getCompanyName() + ".");
        return "application".equals(from)
                ? "redirect:/seeker/applications/" + applicationId
                : "redirect:/seeker/messages/" + applicationId;
    }

    // Everything seeker/message-thread.html needs besides a fresh "messageForm" (left
    // untouched when one already carries a field error from a failed POST, Section 7.2).
    private void addThreadModel(Long applicationId, Long seekerId, Model model) {
        MessageService.ThreadView view = messageService.openThreadForSeeker(applicationId, seekerId);
        model.addAttribute("jobApplication", view.application());
        model.addAttribute("messages", view.messages());
        model.addAttribute("blockedReason", view.blockedReason());
        model.addAttribute("threads", messageService.inboxForSeeker(seekerId));
        model.addAttribute("unreadMessageCount", messageService.unreadCount(seekerId));
        if (!model.containsAttribute("messageForm")) {
            model.addAttribute("messageForm", new MessageForm());
        }
    }
}
