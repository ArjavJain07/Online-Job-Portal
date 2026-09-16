package com.jobportal.web.advice;

import com.jobportal.config.AppProperties;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.MessageRepository;
import com.jobportal.security.AppUserDetails;
import com.jobportal.security.CurrentUser;
import com.jobportal.security.CurrentUserInterceptor;
import com.jobportal.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

// Attributes every page in com.jobportal.web needs (navbar, footer, page title, badges -
// Section 7.1). Scoped to basePackages = "com.jobportal.web" on purpose (Section 7.7): the
// JSON feed controller lives in com.jobportal.api, so these queries never run on every
// poll. GlobalExceptionHandler must NOT copy this scoping - see that class for why.
@ControllerAdvice(basePackages = "com.jobportal.web")
public class GlobalModelAttributes {

    private final SettingsService settingsService;
    private final MessageRepository messageRepository;
    private final JobRepository jobRepository;
    private final AppProperties appProperties;

    public GlobalModelAttributes(SettingsService settingsService, MessageRepository messageRepository,
            JobRepository jobRepository, AppProperties appProperties) {
        this.settingsService = settingsService;
        this.messageRepository = messageRepository;
        this.jobRepository = jobRepository;
        this.appProperties = appProperties;
    }

    // Trims every bound String and turns a blank submission into null, so @NotBlank and
    // optional fields behave predictably (Section 7.3). Also trims passwords, which is
    // harmless: the password pattern allows no spaces at all (4.3).
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ModelAttribute("siteName")
    public String siteName() {
        return settingsService.get().getSiteName();
    }

    @ModelAttribute("announcement")
    public String announcement() {
        return settingsService.get().getAnnouncement();
    }

    // Read from the request attribute CurrentUserInterceptor sets, not from the session
    // principal, so a name or role an admin just changed shows up on this same response.
    @ModelAttribute("currentUser")
    public CurrentUser currentUser(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(CurrentUserInterceptor.CURRENT_USER_ATTRIBUTE);
    }

    // Used by fragments/sidebar to highlight the active menu item.
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    // Navbar envelope badge (employer and seeker). @ModelAttribute methods run before the
    // handler, so the four pages that mark a thread read overwrite this after their bulk
    // update runs (Section 7.10).
    @ModelAttribute("unreadMessageCount")
    public long unreadMessageCount(@AuthenticationPrincipal AppUserDetails me) {
        return me == null ? 0 : messageRepository.countByRecipient_IdAndReadAtIsNull(me.getId());
    }

    // Sidebar "Job approvals" badge, admin only (Section 7.1 sidebar table).
    @ModelAttribute("pendingApprovalCount")
    public long pendingApprovalCount(@AuthenticationPrincipal AppUserDetails me) {
        if (me == null || me.getRole() != Role.ADMIN) {
            return 0;
        }
        return jobRepository.count(JobSpecifications.hasStatus(JobStatus.PENDING_APPROVAL));
    }

    // Shows the "Demo accounts" card on the login page (Section 10.1); off in the test
    // profile and can be turned off before a formal evaluation.
    @ModelAttribute("showDemoCredentials")
    public boolean showDemoCredentials() {
        return appProperties.demo().showCredentials();
    }
}
