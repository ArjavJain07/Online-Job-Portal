package com.jobportal.web.common;

import com.jobportal.config.PasswordResetProperties;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.PasswordResetService;
import com.jobportal.web.form.ForgotPasswordForm;
import com.jobportal.web.form.ResetPasswordForm;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

// Self-service password reset (Section 16 #1, I-17, hard requirement 4). Anonymous-only,
// the same way /login and /register are (Section 6.1 P-3/P-4): SecurityConfig permits both
// routes for anyone, and a logged-in visitor is bounced to their own dashboard exactly like
// AuthController already does for those pages.
//
// THE ONE RULE EVERY HANDLER BELOW FOLLOWS: nothing this controller sends back - response
// body, redirect target, or which of the two reset-password states renders - may depend on
// whether an email address belongs to a real, enabled account. See PasswordResetService's
// class comment for why (Section 4.10's anti-enumeration design, which this feature must
// not undo), and see submitForgotPassword() below for where that rule is actually enforced.
@Controller
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final PasswordResetProperties passwordResetProperties;

    public PasswordResetController(PasswordResetService passwordResetService,
            PasswordResetProperties passwordResetProperties) {
        this.passwordResetService = passwordResetService;
        this.passwordResetProperties = passwordResetProperties;
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordForm(@AuthenticationPrincipal AppUserDetails me, Model model) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        if (!model.containsAttribute("forgotPasswordForm")) {
            model.addAttribute("forgotPasswordForm", new ForgotPasswordForm());
        }
        return "auth/forgot-password";
    }

    // AC-shaped note: a validation error here (blank/malformed email) is safe to show as a
    // normal field error and re-render the form - it is a syntax complaint that applies
    // identically to every visitor, never a statement about who is registered. Only once
    // the form is syntactically valid does control reach the one branch that must give an
    // identical answer for a known and an unknown address: the flash message below is set
    // unconditionally, on the same code path, whatever requestReset() actually did.
    @PostMapping("/forgot-password")
    public String submitForgotPassword(@AuthenticationPrincipal AppUserDetails me,
            @Valid @ModelAttribute("forgotPasswordForm") ForgotPasswordForm form, BindingResult result,
            RedirectAttributes redirectAttributes) {
        if (me != null) {
            return "redirect:/dashboard";
        }
        if (result.hasErrors()) {
            return "auth/forgot-password";
        }

        String resetUrlBase = ServletUriComponentsBuilder.fromCurrentContextPath().path("/reset-password").toUriString();
        passwordResetService.requestReset(form.getEmail(), resetUrlBase);

        redirectAttributes.addFlashAttribute("success",
                "If an account exists for that email address, we've emailed a link to reset the password. "
                        + "The link expires in " + passwordResetProperties.tokenExpiryMinutes() + " minutes.");
        return "redirect:/forgot-password";
    }

    // token is optional at the binding level purely so a bare GET /reset-password (no
    // query string at all) renders the same "link no longer valid" state as any other bad
    // token, instead of Spring's usual 400 for a missing required parameter.
    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(required = false) String token, Model model) {
        if (!passwordResetService.isValidToken(token)) {
            model.addAttribute("tokenInvalid", true);
            return "auth/reset-password";
        }
        if (!model.containsAttribute("resetPasswordForm")) {
            ResetPasswordForm form = new ResetPasswordForm();
            form.setToken(token);
            model.addAttribute("resetPasswordForm", form);
        }
        return "auth/reset-password";
    }

    // Re-checks the token before looking at validation errors: an expired/used/unknown
    // token means there is no account to report a password-strength error against, so the
    // page must drop straight to the same "link no longer valid" state the GET handler
    // uses, not a form the visitor cannot possibly submit successfully.
    @PostMapping("/reset-password")
    public String submitResetPassword(@Valid @ModelAttribute("resetPasswordForm") ResetPasswordForm form,
            BindingResult result, Model model) {
        if (!passwordResetService.isValidToken(form.getToken())) {
            model.addAttribute("tokenInvalid", true);
            return "auth/reset-password";
        }
        if (result.hasErrors()) {
            return "auth/reset-password";
        }

        // A BusinessRuleException here (PasswordResetService re-validates the token at
        // the point of writing) is the rare race of two submissions for the same token -
        // left to GlobalExceptionHandler, which flashes it and redirects back to this same
        // page (Referer), where isValidToken() now correctly shows "link no longer valid".
        passwordResetService.resetPassword(form.getToken(), form.getNewPassword());
        return "redirect:/login?reset";
    }
}
