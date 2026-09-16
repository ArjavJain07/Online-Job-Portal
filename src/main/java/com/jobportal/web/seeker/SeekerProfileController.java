package com.jobportal.web.seeker;

import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.JobType;
import com.jobportal.exception.FileValidationException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.FileStorageService;
import com.jobportal.service.SeekerProfileService;
import com.jobportal.service.SettingsService;
import com.jobportal.service.UserAccountService;
import com.jobportal.web.form.ResumeUploadForm;
import com.jobportal.web.form.SeekerProfileForm;
import com.jobportal.web.support.FileResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;

// Seeker profile and resume (Section 6.4 S-F4/S-D3, Section 6.5.2, 6.6 route summary).
// Thin controller (11.3 contract item 1): every rule and side effect lives in
// SeekerProfileService, this class only wires the form/PRG plumbing, the same split
// EmployerProfileController uses for the company profile.
@Controller
public class SeekerProfileController {

    private final SeekerProfileService seekerProfileService;
    private final FileStorageService fileStorageService;
    private final SettingsService settingsService;

    public SeekerProfileController(SeekerProfileService seekerProfileService, FileStorageService fileStorageService,
            SettingsService settingsService) {
        this.seekerProfileService = seekerProfileService;
        this.fileStorageService = fileStorageService;
        this.settingsService = settingsService;
    }

    @GetMapping("/seeker/profile")
    public String form(@AuthenticationPrincipal AppUserDetails me, Model model) {
        addProfileModel(me.getId(), model);
        return "seeker/profile";
    }

    // A changed email logs the acting seeker out (Section 6.4 S-F4 business rule "Email
    // change forces a new login"), the same pattern EmployerProfileController#save uses.
    @PostMapping("/seeker/profile")
    public String save(@Valid @ModelAttribute("seekerProfileForm") SeekerProfileForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, HttpServletRequest request, HttpServletResponse response,
            Model model, RedirectAttributes redirect) {
        if (!result.hasFieldErrors("email") && seekerProfileService.emailInUse(form.getEmail(), me.getId())) {
            result.rejectValue("email", "duplicate", UserAccountService.DUPLICATE_EMAIL_MESSAGE);
        }
        if (result.hasErrors()) {
            addProfileModel(me.getId(), model);
            return "seeker/profile";
        }

        SeekerProfileService.UpdateOutcome outcome = seekerProfileService.save(me.getId(), form);
        if (outcome.emailChanged()) {
            new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
            return "redirect:/login?emailChanged";
        }
        redirect.addFlashAttribute("success", "Profile updated successfully.");
        return "redirect:/seeker/profile";
    }

    // Separate form from the one above (Section 6.4 S-F4 "Resume card" note: "so a
    // profile validation error never discards a chosen file"). No @Valid: every rule is
    // enforced by FileStorageService.store() against the current settings (Section 7.4),
    // and its FileValidationException is turned into a field error on "resumeFile" here,
    // the same field name every resume upload form in the app uses (Section 7.3 table).
    @PostMapping("/seeker/profile/resume")
    public String uploadResume(@ModelAttribute("resumeForm") ResumeUploadForm form, BindingResult result,
            @AuthenticationPrincipal AppUserDetails me, Model model, RedirectAttributes redirect) {
        try {
            seekerProfileService.uploadResume(me.getId(), form.getResumeFile());
        } catch (FileValidationException e) {
            result.rejectValue("resumeFile", "fileValidation", e.getMessage());
            addProfileModel(me.getId(), model);
            return "seeker/profile";
        }
        redirect.addFlashAttribute("success", "Resume uploaded successfully.");
        return "redirect:/seeker/profile";
    }

    // Simple POST button with data-confirm (Section 7.1): no BusinessRuleException is
    // possible here (removing when nothing is uploaded is harmless), so no try/catch.
    @PostMapping("/seeker/profile/resume/delete")
    public String deleteResume(@AuthenticationPrincipal AppUserDetails me, RedirectAttributes redirect) {
        seekerProfileService.removeResume(me.getId());
        redirect.addFlashAttribute("success", "Resume removed. You'll need to upload one when applying for jobs.");
        return "redirect:/seeker/profile";
    }

    // The seeker's own current profile resume (Section 6.5.2). Always loaded by the
    // logged-in seeker's own id (Section 4.5: "Not possible" to reach another seeker's
    // profile resume, there is no id in the URL to forge). A hand-crafted GET with no
    // resume uploaded yet is left as the ordinary 404 page; a missing file on disk gets
    // the flash-and-redirect Section 7.4 asks for instead, the same split
    // EmployerApplicationController#resume uses for the application copy.
    @GetMapping("/seeker/profile/resume")
    public ResponseEntity<Resource> viewResume(@AuthenticationPrincipal AppUserDetails me, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        SeekerProfile profile = seekerProfileService.loadProfile(me.getId());
        if (profile.getResumeStoredName() == null) {
            throw new ResourceNotFoundException("No resume has been uploaded yet.");
        }
        try {
            Resource resource = fileStorageService.load(profile.getResumeStoredName());
            return FileResponses.serve(resource, profile.getResumeOriginalName(), profile.getResumeContentType());
        } catch (ResourceNotFoundException e) {
            redirectWithFlash(request, response, "/seeker/profile", e.getMessage());
            return null;
        }
    }

    // Same hand-built redirect-with-flash EmployerApplicationController#resume uses: this
    // route returns a file, not a view name, so RedirectAttributes is not available.
    private void redirectWithFlash(HttpServletRequest request, HttpServletResponse response, String targetPath,
            String message) throws IOException {
        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        flashMap.put("error", message);
        flashMap.setTargetRequestPath(request.getContextPath() + targetPath);
        RequestContextUtils.getFlashMapManager(request).saveOutputFlashMap(flashMap, request, response);
        response.sendRedirect(request.getContextPath() + targetPath);
    }

    // Everything seeker/profile.html needs besides whichever of the two forms was just
    // submitted (already in the model from @ModelAttribute/BindingResult when this is
    // called after a failed POST, Section 7.2) - the profile row itself (for the resume
    // card), its completeness bar, and the preferredJobType select options (Section 7.1
    // note: an enum is resolved once here, not looped with T(...) in the template).
    private void addProfileModel(Long seekerId, Model model) {
        SeekerProfile profile = seekerProfileService.loadProfile(seekerId);
        if (!model.containsAttribute("seekerProfileForm")) {
            model.addAttribute("seekerProfileForm", toForm(seekerProfileService.loadUser(seekerId), profile));
        }
        if (!model.containsAttribute("resumeForm")) {
            model.addAttribute("resumeForm", new ResumeUploadForm());
        }
        model.addAttribute("profile", profile);
        model.addAttribute("completeness", seekerProfileService.completeness(profile));
        model.addAttribute("jobTypes", JobType.values());

        SystemSettings settings = settingsService.get();
        model.addAttribute("resumeAccept", resumeAccept(settings));
        model.addAttribute("resumeHint", resumeHint(settings));
    }

    // ".pdf,.doc,.docx" for the file input's accept attribute, and "PDF, DOC or DOCX, max
    // 2 MB" for its help text (Section 7.4, Section 6.4 S-F2 screen note "accept built
    // from the allowed types" - the resume card here follows the same rule). Built from
    // SettingsService directly, in the controller, the same place JobFormOptions is built
    // (Section 7.1 note) - small enough to duplicate rather than reach into
    // JobApplicationService.resumeOptions(...) for the S-F2 apply page's own version of
    // this text, which would tie this profile-only page to that slice's file.
    private String resumeAccept(SystemSettings settings) {
        List<String> parts = new ArrayList<>();
        for (String type : allowedTypes(settings)) {
            parts.add("." + type);
        }
        return String.join(",", parts);
    }

    private String resumeHint(SystemSettings settings) {
        List<String> upper = new ArrayList<>();
        for (String type : allowedTypes(settings)) {
            upper.add(type.toUpperCase(Locale.ROOT));
        }
        String joined = upper.size() == 1 ? upper.get(0)
                : String.join(", ", upper.subList(0, upper.size() - 1)) + " or " + upper.get(upper.size() - 1);
        return joined + ", max " + settings.getMaxResumeSizeMb() + " MB";
    }

    private List<String> allowedTypes(SystemSettings settings) {
        List<String> types = new ArrayList<>();
        for (String type : settings.getAllowedResumeTypes().split(",")) {
            String trimmed = type.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types;
    }

    private SeekerProfileForm toForm(User user, SeekerProfile profile) {
        SeekerProfileForm form = new SeekerProfileForm();
        form.setFullName(user.getFullName());
        form.setEmail(user.getEmail());
        form.setPhone(profile.getPhone());
        form.setLocation(profile.getLocation());
        form.setHeadline(profile.getHeadline());
        form.setSkills(profile.getSkills());
        form.setExperienceYears(profile.getExperienceYears());
        form.setPreferredJobType(profile.getPreferredJobType());
        form.setEducation(profile.getEducation());
        form.setAbout(profile.getAbout());
        return form;
    }
}
