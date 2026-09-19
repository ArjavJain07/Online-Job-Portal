package com.jobportal.web.common;

import com.jobportal.security.AppUserDetails;
import com.jobportal.security.LoginFailureHandler;
import com.jobportal.service.SettingsService;
import com.jobportal.service.UserAccountService;
import com.jobportal.web.form.RegisterEmployerForm;
import com.jobportal.web.form.RegisterSeekerForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

// Registration and login pages (Section 6.1 P-3, P-4). POST /login and POST /logout are
// handled by Spring Security itself (SecurityConfig), not here.
@Controller
public class AuthController {

    private final SettingsService settingsService;
    private final UserAccountService userAccountService;
    private final Clock clock;

    public AuthController(SettingsService settingsService, UserAccountService userAccountService, Clock clock) {
        this.settingsService = settingsService;
        this.userAccountService = userAccountService;
        this.clock = clock;
    }

    // GET /login: a logged-in user is sent to their own dashboard (Section 4.3). The
    // login-page messages (?error, ?blocked, ?changed, ?emailChanged, ?logout,
    // ?registered - Section 4.4) are read straight from the query string by the template
    // itself, so no model attribute is needed for those.
    //
    // ?locked (Section 4.10) is the exception: it needs a number, and that number must
    // not come from the URL. The countdown is computed here from the unlock instant
    // LoginFailureHandler parked in the session, so the only visitor who can ever see it
    // is the one whose own correct password was refused a moment ago. This page is
    // anonymous, so it must never look an account up by the email in a parameter - that
    // would be the very enumeration oracle the feature exists to avoid.
    @GetMapping("/login")
    public String login(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request, Model model) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        readLockoutCountdown(request).ifPresent(minutes -> model.addAttribute("lockedForMinutes", minutes));
        return "auth/login";
    }

    // Minutes left on the cooldown, rounded up so "59 seconds to go" reads as 1 minute
    // rather than 0. Read-once: the attribute is removed whether or not it is still in
    // the future, so a stale value cannot reappear on the next visit to the page. Never
    // creates a session (getSession(false)); Spring Security has already made one for the
    // CSRF token by the time a login can fail.
    private Optional<Long> readLockoutCountdown(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object parked = session.getAttribute(LoginFailureHandler.LOCKED_UNTIL_ATTRIBUTE);
        if (!(parked instanceof LocalDateTime lockedUntil)) {
            return Optional.empty();
        }
        session.removeAttribute(LoginFailureHandler.LOCKED_UNTIL_ATTRIBUTE);
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(clock), lockedUntil);
        return seconds > 0 ? Optional.of((seconds + 59) / 60) : Optional.empty();
    }

    // GET /register: the chooser page hides a card when that role's registration is
    // closed (same rule as the landing page's CTAs, P-1).
    @GetMapping("/register")
    public String chooseRole(@AuthenticationPrincipal AppUserDetails me, Model model) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("seekerRegistrationOpen", settingsService.get().isSeekerRegistrationOpen());
        model.addAttribute("employerRegistrationOpen", settingsService.get().isEmployerRegistrationOpen());
        return "auth/register-choose";
    }

    @GetMapping("/register/seeker")
    public String seekerForm(@AuthenticationPrincipal AppUserDetails me, Model model) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        if (registrationClosed(settingsService.get().isSeekerRegistrationOpen(), "Job seeker", model)) {
            return "auth/registration-closed";
        }
        if (!model.containsAttribute("registerSeekerForm")) {
            model.addAttribute("registerSeekerForm", new RegisterSeekerForm());
        }
        return "auth/register-seeker";
    }

    // AC-P3-1, AC-P3-2, AC-P3-3, AC-P3-4. The setting is checked again here (not just on
    // the GET) so a POST sent after registration just closed still creates nothing.
    @PostMapping("/register/seeker")
    public String registerSeeker(@Valid @ModelAttribute("registerSeekerForm") RegisterSeekerForm form,
            BindingResult result, Model model) {
        if (registrationClosed(settingsService.get().isSeekerRegistrationOpen(), "Job seeker", model)) {
            return "auth/registration-closed";
        }
        if (!result.hasFieldErrors("email") && userAccountService.emailExists(form.getEmail())) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            return "auth/register-seeker";
        }
        userAccountService.registerSeeker(form);
        return "redirect:/login?registered";
    }

    @GetMapping("/register/employer")
    public String employerForm(@AuthenticationPrincipal AppUserDetails me, Model model) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        if (registrationClosed(settingsService.get().isEmployerRegistrationOpen(), "Employer", model)) {
            return "auth/registration-closed";
        }
        if (!model.containsAttribute("registerEmployerForm")) {
            model.addAttribute("registerEmployerForm", new RegisterEmployerForm());
        }
        return "auth/register-employer";
    }

    @PostMapping("/register/employer")
    public String registerEmployer(@Valid @ModelAttribute("registerEmployerForm") RegisterEmployerForm form,
            BindingResult result, Model model) {
        if (registrationClosed(settingsService.get().isEmployerRegistrationOpen(), "Employer", model)) {
            return "auth/registration-closed";
        }
        if (!result.hasFieldErrors("email") && userAccountService.emailExists(form.getEmail())) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            return "auth/register-employer";
        }
        userAccountService.registerEmployer(form);
        return "redirect:/login?registered";
    }

    // Shared by all four registration handlers (Section 4.3): when the setting is off,
    // GET and POST both render auth/registration-closed.html and create nothing.
    private boolean registrationClosed(boolean open, String roleLabel, Model model) {
        if (open) {
            return false;
        }
        model.addAttribute("message", roleLabel + " registration is currently closed. Please check back later.");
        return true;
    }
}
