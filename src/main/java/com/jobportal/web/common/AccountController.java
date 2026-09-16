package com.jobportal.web.common;

import com.jobportal.exception.BusinessRuleException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.UserAccountService;
import com.jobportal.web.form.ChangePasswordForm;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Change password page (Section 6.1 P-5). Reachable by any authenticated role -
// SecurityConfig has no role restriction on /account/**, just anyRequest().authenticated().
@Controller
public class AccountController {

    private final UserAccountService userAccountService;

    public AccountController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @GetMapping("/account/password")
    public String form(Model model) {
        if (!model.containsAttribute("changePasswordForm")) {
            model.addAttribute("changePasswordForm", new ChangePasswordForm());
        }
        return "account/change-password";
    }

    // AC-P5-1: wrong current password or a new password equal to the old one is reported
    // as a form error (Section 7.2's BusinessRuleException-in-a-form-controller pattern);
    // success flashes "Password changed successfully." and stays on the same page (PRG).
    @PostMapping("/account/password")
    public String change(@Valid @ModelAttribute("changePasswordForm") ChangePasswordForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            return "account/change-password";
        }
        try {
            userAccountService.changePassword(me.getId(), form);
        } catch (BusinessRuleException e) {
            result.reject("businessRule", e.getMessage());
            return "account/change-password";
        }
        redirectAttributes.addFlashAttribute("success", "Password changed successfully.");
        return "redirect:/account/password";
    }
}
