package com.jobportal.web.common;

import com.jobportal.service.JobAlertService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

// The no-login-required half of job alerts (Section 16 future-work item 5): every digest
// email ends with this link (NotificationService.notifyJobAlertDigest), and it must keep
// working for a signed-out visitor - or one signed in as someone else entirely - exactly
// the way it would for the account the link actually names. Modelled directly on
// PasswordResetController's GET-preview/POST-consumes split (see that class and
// JobAlertService for the token itself), but with none of PasswordResetService's
// anti-enumeration care: there is no equivalent secret to protect here (an unsubscribe
// click reveals nothing about who has an account), so this controller is free to say
// plainly whether a link is valid.
@Controller
public class JobAlertUnsubscribeController {

    private final JobAlertService jobAlertService;

    public JobAlertUnsubscribeController(JobAlertService jobAlertService) {
        this.jobAlertService = jobAlertService;
    }

    // token is optional at the binding level for the same reason ResetPasswordController's
    // is (PasswordResetController): a bare GET with no query string at all renders the same
    // "invalid link" state as any other bad token, instead of Spring's usual 400 for a
    // missing required parameter. This GET never itself unsubscribes anyone - see
    // JobAlertService.isValidUnsubscribeToken's own comment on why a read-only preview
    // matters here (mail clients that pre-fetch links).
    @GetMapping("/job-alerts/unsubscribe")
    public String confirm(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("tokenValid", jobAlertService.isValidUnsubscribeToken(token));
        model.addAttribute("unsubscribed", false);
        model.addAttribute("token", token);
        return "public/job-alert-unsubscribe";
    }

    // Re-checks the token itself rather than trusting the GET above (defence in depth,
    // same reasoning as PasswordResetController#submitResetPassword) and never throws the
    // BusinessRuleException uncaught - a stale/hand-edited link falls straight back to the
    // same "invalid" state the GET shows, not a generic error flash on some unrelated page.
    @PostMapping("/job-alerts/unsubscribe")
    public String unsubscribe(@RequestParam(required = false) String token, Model model) {
        if (!jobAlertService.isValidUnsubscribeToken(token)) {
            model.addAttribute("tokenValid", false);
            model.addAttribute("unsubscribed", false);
            return "public/job-alert-unsubscribe";
        }
        jobAlertService.unsubscribeByToken(token);
        model.addAttribute("tokenValid", true);
        model.addAttribute("unsubscribed", true);
        return "public/job-alert-unsubscribe";
    }
}
