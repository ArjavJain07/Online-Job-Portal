package com.jobportal.web.admin;

import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.repository.UserRepository;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.SettingsService;
import com.jobportal.web.form.SettingsForm;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// System settings screen (Section 6.2 A-F3, A-D3; field definitions in 7.5). One form in
// five cards; a failed validation re-renders the same template with nothing saved
// (Section 7.2/7.3), a successful save redirects back to the same page (PRG).
@Controller
@RequestMapping("/admin/settings")
public class AdminSettingsController {

    private final SettingsService settingsService;
    private final UserRepository userRepository;

    public AdminSettingsController(SettingsService settingsService, UserRepository userRepository) {
        this.settingsService = settingsService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String form(Model model) {
        if (!model.containsAttribute("settingsForm")) {
            model.addAttribute("settingsForm", toForm(settingsService.get()));
        }
        addLastSaved(model);
        return "admin/settings";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("settingsForm") SettingsForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            model.addAttribute("error", "Settings were not saved. Please fix the highlighted fields.");
            addLastSaved(model);
            return "admin/settings";
        }

        // SettingsService.save(form, admin) logs the actor's name, so it needs the full
        // User entity, not just the session's AppUserDetails (Section 4.6/7.5).
        User admin = userRepository.findById(me.getId())
                .orElseThrow(() -> new IllegalStateException("Logged-in admin no longer exists: " + me.getId()));
        settingsService.save(form, admin);

        redirectAttributes.addFlashAttribute("success", "Settings saved. Changes apply immediately.");
        return "redirect:/admin/settings";
    }

    // Prefills the form with the settings row's current values so "each field shows its
    // current value" (Section 6.2) on first load.
    private SettingsForm toForm(SystemSettings settings) {
        SettingsForm form = new SettingsForm();
        form.setSiteName(settings.getSiteName());
        form.setAnnouncement(settings.getAnnouncement());
        form.setSeekerRegistrationOpen(settings.isSeekerRegistrationOpen());
        form.setEmployerRegistrationOpen(settings.isEmployerRegistrationOpen());
        form.setJobApprovalRequired(settings.isJobApprovalRequired());
        form.setMaxActiveJobsPerEmployer(settings.getMaxActiveJobsPerEmployer());
        form.setMaxResumeSizeMb(settings.getMaxResumeSizeMb());
        form.setAllowedResumeTypes(parseAllowedTypes(settings.getAllowedResumeTypes()));
        form.setPageSize(settings.getPageSize());
        form.setFeedRefreshSeconds(settings.getFeedRefreshSeconds());
        return form;
    }

    private List<String> parseAllowedTypes(String csv) {
        List<String> types = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String type : csv.split(",")) {
                types.add(type.trim().toLowerCase(Locale.ROOT));
            }
        }
        return types;
    }

    // "Last saved 16 Sep 2026 10:42 by Site Admin" at the top of the screen (Section 6.2).
    // The plan gives no wording for a settings row that has never been saved (a fresh
    // seed, before any admin has opened this page): DataSeeder only writes the id-1 row
    // with the 7.5 defaults and leaves updatedAt/updatedBy null (SettingsService), so this
    // is the simplest case the template has to handle.
    private void addLastSaved(Model model) {
        SystemSettings settings = settingsService.get();
        model.addAttribute("lastSavedAt", settings.getUpdatedAt());
        model.addAttribute("lastSavedBy", settings.getUpdatedBy());
    }
}
