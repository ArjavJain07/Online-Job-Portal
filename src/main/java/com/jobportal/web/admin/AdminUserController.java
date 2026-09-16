package com.jobportal.web.admin;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.UserAccountService;
import com.jobportal.service.UserService;
import com.jobportal.web.form.UserForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Admin user management (Section 6.2 A-F1/A-D1, 6.6 route summary). Thin controller
// (11.3 contract item 1): every rule, side effect and activity log entry lives in
// UserService, this class only wires the form/PRG plumbing.
@Controller
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    // GET /admin/users?q=&role=&status=&page= (A-D1). q/role/status/page are left as raw,
    // optional strings and parsed by UserService with a fallback (Section 7.9 binding
    // rule), so a stray value in the URL never produces an error page.
    @GetMapping("/admin/users")
    public String list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(required = false) String page, Model model) {
        Page<User> users = userService.search(q, role, status, page);
        model.addAttribute("page", users);
        model.addAttribute("q", q);
        model.addAttribute("role", role);
        model.addAttribute("status", status);
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @GetMapping("/admin/users/new")
    public String newForm(Model model) {
        if (!model.containsAttribute("userForm")) {
            UserForm form = new UserForm();
            form.setEnabled(true);
            model.addAttribute("userForm", form);
        }
        addFormModel(model, null, false);
        return "admin/user-form";
    }

    // AC-A-F1-1/AC-A-F1-2: duplicate email and a missing employer company name are shown
    // as field errors (the second one comes from UserForm's own @AssertTrue); everything
    // else that refuses the create is a BusinessRuleException from the service, shown as
    // a global form error (Section 7.2 pattern).
    @PostMapping("/admin/users")
    public String create(@Valid @ModelAttribute("userForm") UserForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        if (form.getNewPassword() == null || form.getNewPassword().isBlank()) {
            result.rejectValue("newPassword", "required", UserForm.PASSWORD_MESSAGE);
        }
        if (!result.hasFieldErrors("email") && userService.emailInUse(form.getEmail(), null)) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            addFormModel(model, null, false);
            return "admin/user-form";
        }
        User user = userService.create(form, me.getId());
        redirect.addFlashAttribute("success", "User " + user.getFullName() + " created.");
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/users/{id}/edit")
    public String editForm(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        User user = userService.findById(id);
        if (!model.containsAttribute("userForm")) {
            model.addAttribute("userForm", toForm(user));
        }
        addFormModel(model, id, id.equals(me.getId()));
        return "admin/user-form";
    }

    // AC-A-F1-5/AC-A-F1-6: role/status self-protection, the last-active-admin rule and the
    // role-change dependency check all arrive as a BusinessRuleException from the service
    // and become a global form error here (Section 7.2). A changed email on the acting
    // admin's own row logs them out instead of showing the usual flash (Section 6.2 A-F1
    // rule 3, Section 4.6) - that needs the request/response, so it happens here, not in
    // the service.
    @PostMapping("/admin/users/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("userForm") UserForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, HttpServletRequest request, HttpServletResponse response,
            Model model, RedirectAttributes redirect) {
        boolean self = id.equals(me.getId());
        if (!result.hasFieldErrors("email") && userService.emailInUse(form.getEmail(), id)) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            addFormModel(model, id, self);
            return "admin/user-form";
        }
        try {
            UserService.UpdateOutcome outcome = userService.update(id, form, me.getId());
            if (self && outcome.emailChanged()) {
                new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
                return "redirect:/login?emailChanged";
            }
            String message = "User " + outcome.user().getFullName() + " updated."
                    + (outcome.passwordChanged() ? " The new password works immediately." : "");
            redirect.addFlashAttribute("success", message);
            return "redirect:/admin/users";
        } catch (BusinessRuleException e) {
            result.reject("businessRule", e.getMessage());
            addFormModel(model, id, self);
            return "admin/user-form";
        }
    }

    // Simple POST button: does not catch BusinessRuleException (Section 7.2), so a
    // refused deactivation/activation (self, last admin) reaches GlobalExceptionHandler
    // and comes back as an "error" flash on the page the button was on.
    @PostMapping("/admin/users/{id}/toggle-status")
    public String toggleStatus(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {
        User user = userService.toggleStatus(id, me.getId());
        String verb = user.isEnabled() ? " activated." : " deactivated.";
        redirect.addFlashAttribute("success", "User " + userService.displayLabel(user) + verb);
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/users/{id}/delete")
    public String confirmDelete(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, Model model) {
        User user = userService.findById(id);
        UserService.DependencyCounts counts = userService.dependencyCounts(id);
        model.addAttribute("targetUser", user);
        model.addAttribute("counts", counts);
        model.addAttribute("canDelete", !counts.any());
        model.addAttribute("blockedMessage", counts.any() ? userService.blockedDeleteMessage(counts) : null);
        model.addAttribute("isSelf", id.equals(me.getId()));
        return "admin/user-delete";
    }

    // Simple POST button (Section 7.2): a refused delete (self, last admin, dependencies)
    // reaches GlobalExceptionHandler, which flashes the message and redirects back to the
    // confirmation page (the Referer).
    @PostMapping("/admin/users/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me,
            RedirectAttributes redirect) {
        User deleted = userService.delete(id, me.getId());
        redirect.addFlashAttribute("success", "User " + userService.displayLabel(deleted) + " deleted.");
        return "redirect:/admin/users";
    }

    private UserForm toForm(User user) {
        UserForm form = new UserForm();
        form.setFullName(user.getFullName());
        form.setEmail(user.getEmail());
        form.setRole(user.getRole());
        form.setEnabled(user.isEnabled());
        form.setCompanyName(user.getCompanyName());
        return form;
    }

    // Every model attribute admin/user-form.html needs besides "userForm": the roles
    // list (Section 7.1: an enum can't be looped with T(...) in the template itself), the
    // computed form action and page title (built here, not with a template-side ternary
    // inside a layout(...) fragment call - every other page passes a plain, already
    // resolved value there, e.g. job-review.html's title=${job.title}), the id (null on
    // create) and the self-editing flag.
    private void addFormModel(Model model, Long id, boolean editingSelf) {
        model.addAttribute("roles", Role.values());
        model.addAttribute("userId", id);
        model.addAttribute("formAction", id == null ? "/admin/users" : "/admin/users/" + id);
        model.addAttribute("pageTitle", id == null ? "Create user" : "Edit user");
        model.addAttribute("editingSelf", editingSelf);
    }
}
