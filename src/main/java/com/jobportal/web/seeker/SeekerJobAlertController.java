package com.jobportal.web.seeker;

import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobAlertService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The seeker's own job-alerts settings (Section 16 future-work item 5): a single opt-in
// toggle plus when the last digest went out - not a per-frequency picker, see
// JobAlertService's class comment for why one fixed weekly cadence (an operator setting,
// app.job-alerts.digest-frequency-days) was chosen over letting each seeker pick their own.
// The other half of "opt out" - the emailed, no-login unsubscribe link - is
// JobAlertUnsubscribeController (web.common), a separate, anonymous-accessible controller
// because this one sits behind /seeker/** (SecurityConfig: ROLE_JOB_SEEKER, login
// required), which a "no login needed" link cannot.
@Controller
public class SeekerJobAlertController {

    private final JobAlertService jobAlertService;

    public SeekerJobAlertController(JobAlertService jobAlertService) {
        this.jobAlertService = jobAlertService;
    }

    @GetMapping("/seeker/job-alerts")
    public String settings(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("status", jobAlertService.status(me.getId()));
        return "seeker/job-alerts";
    }

    // Simple POST button (Section 7.2): opts the seeker in (or confirms they already are).
    @PostMapping("/seeker/job-alerts")
    public String subscribe(@AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        jobAlertService.subscribe(me.getId());
        redirect.addFlashAttribute("success",
                "Job alerts are on. We'll email you when new jobs match your profile.");
        return "redirect:/seeker/job-alerts";
    }

    // Self-service opt-out while logged in, distinct from (but ending in the same state
    // as) the emailed unsubscribe link - see JobAlertService.unsubscribeSelf/unsubscribeByToken.
    @PostMapping("/seeker/job-alerts/disable")
    public String unsubscribe(@AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        jobAlertService.unsubscribeSelf(me.getId());
        redirect.addFlashAttribute("success", "Job alerts are off. You can turn them back on any time.");
        return "redirect:/seeker/job-alerts";
    }
}
