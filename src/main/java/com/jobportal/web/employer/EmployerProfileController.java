package com.jobportal.web.employer;

import com.jobportal.domain.User;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.EmployerProfileService;
import com.jobportal.service.UserAccountService;
import com.jobportal.web.form.EmployerProfileForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Company profile (Section 6.3 EP, 6.6 route summary). Thin controller (11.3 contract
// item 1): every rule and side effect lives in EmployerProfileService.
@Controller
public class EmployerProfileController {

    private final EmployerProfileService employerProfileService;

    public EmployerProfileController(EmployerProfileService employerProfileService) {
        this.employerProfileService = employerProfileService;
    }

    @GetMapping("/employer/profile")
    public String form(@AuthenticationPrincipal AppUserDetails me, Model model) {
        if (!model.containsAttribute("employerProfileForm")) {
            model.addAttribute("employerProfileForm", toForm(employerProfileService.findById(me.getId())));
        }
        return "employer/profile";
    }

    // A changed email logs the acting employer out (Section 6.3 EP), the same pattern
    // AdminUserController#update uses on the admin's own row (Section 4.6, 6.2 A-F1 rule
    // 3) - that needs the request/response, so the decision is made here, not in the
    // service.
    @PostMapping("/employer/profile")
    public String save(@Valid @ModelAttribute("employerProfileForm") EmployerProfileForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, HttpServletRequest request, HttpServletResponse response,
            RedirectAttributes redirect) {
        if (!result.hasFieldErrors("email") && employerProfileService.emailInUse(form.getEmail(), me.getId())) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            return "employer/profile";
        }

        EmployerProfileService.UpdateOutcome outcome = employerProfileService.save(me.getId(), form);
        if (outcome.emailChanged()) {
            new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
            return "redirect:/login?emailChanged";
        }
        redirect.addFlashAttribute("success", "Company profile updated.");
        return "redirect:/employer/profile";
    }

    private EmployerProfileForm toForm(User employer) {
        EmployerProfileForm form = new EmployerProfileForm();
        form.setFullName(employer.getFullName());
        form.setEmail(employer.getEmail());
        form.setCompanyName(employer.getCompanyName());
        form.setCompanyWebsite(employer.getCompanyWebsite());
        form.setCompanyDescription(employer.getCompanyDescription());
        return form;
    }
}
