package com.jobportal.service;

import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.web.form.UserForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Admin user management (Section 6.2 A-F1/A-D1): list/filter, create, update, activate/
// deactivate and delete any account, plus the self-protection and last-admin rules of
// Section 4.6 and 1.6 D-12/D-16. UserAccountService (owned by another slice) already
// covers self-service registration and change-password; this class never touches those
// two flows so the two services cannot drift apart (see the note on UserAccountService).
@Service
public class UserService {

    // Flash/business-rule messages, copied character-for-character from Section 6.2 A-F1
    // so controllers and (later) tests can reuse the exact wording instead of retyping it.
    public static final String SELF_ROLE_OR_STATUS_MESSAGE = "You can't change the role or status of your own account.";
    public static final String SELF_DELETE_OR_DEACTIVATE_MESSAGE = "You can't deactivate or delete your own account.";
    public static final String LAST_ADMIN_MESSAGE = "At least one active admin account is required.";
    public static final String ROLE_CHANGE_BLOCKED_MESSAGE =
            "Role can't be changed because this user has jobs, applications or messages. Create a new account instead.";

    private final UserRepository userRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final MessageRepository messageRepository;
    private final ActivityLogService activityLogService;
    private final FileStorageService fileStorageService;
    private final SettingsService settingsService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository userRepository, SeekerProfileRepository seekerProfileRepository,
            JobRepository jobRepository, JobApplicationRepository jobApplicationRepository,
            MessageRepository messageRepository, ActivityLogService activityLogService,
            FileStorageService fileStorageService, SettingsService settingsService, PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userRepository = userRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.jobRepository = jobRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.messageRepository = messageRepository;
        this.activityLogService = activityLogService;
        this.fileStorageService = fileStorageService;
        this.settingsService = settingsService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ---- Reads (A-D1) ----

    // q/role/status arrive as raw, optional strings straight from the query string
    // (Section 7.9 binding rule): an unparsable role or status is treated as "Any"
    // instead of raising an error, and a bad page number falls back to 0. Ordered newest
    // registered first (A-D1).
    public Page<User> search(String rawQ, String rawRole, String rawStatus, String rawPage) {
        String q = normaliseQuery(rawQ);
        Role role = parseRole(rawRole);
        Boolean enabled = parseStatus(rawStatus);
        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return userRepository.search(q, role, enabled, pageable);
    }

    public User findById(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist"));
    }

    // Delete-confirmation dependency summary (Section 5.8): "Jobs: N * Applications: N *
    // Messages: N", shown whatever the user's role is.
    public DependencyCounts dependencyCounts(Long userId) {
        long jobs = jobRepository.countByEmployer_Id(userId);
        long applications = jobApplicationRepository.countBySeeker_Id(userId);
        long messages = messageRepository.countBySender_IdOrRecipient_Id(userId, userId);
        return new DependencyCounts(jobs, applications, messages);
    }

    // ---- Create (A-F1) ----

    // AC-A-F1-1: sets enabled from the form, hashes the temporary password, creates an
    // empty SeekerProfile for a job seeker, logs USER_CREATED. The duplicate-email check
    // (with the field error) runs in the controller before this is called, the same
    // pattern AuthController uses for registration (Section 7.2).
    @Transactional
    public User create(UserForm form, Long adminId) {
        User admin = loadAdmin(adminId);

        User user = new User();
        user.setFullName(form.getFullName());
        user.setEmail(normaliseEmail(form.getEmail()));
        user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
        user.setRole(form.getRole());
        user.setEnabled(form.isEnabled());
        user.setCreatedAt(LocalDateTime.now(clock));
        if (form.getRole() == Role.EMPLOYER) {
            user.setCompanyName(form.getCompanyName());
        }
        userRepository.saveAndFlush(user);

        if (form.getRole() == Role.JOB_SEEKER) {
            SeekerProfile profile = new SeekerProfile();
            profile.setUser(user);
            profile.setExperienceYears(0);
            seekerProfileRepository.save(profile);
        }

        String description = admin.getFullName() + " created " + form.getRole().getLabel().toLowerCase(Locale.ROOT)
                + " account " + user.getFullName();
        activityLogService.log(ActivityType.USER_CREATED, admin, description, TargetType.USER, user.getId());
        return user;
    }

    // ---- Update (A-F1) ----

    // Rule 2/3/4/6 of Section 6.2 A-F1: role/status changes are blocked for the admin's
    // own row and for the last active admin; a role change is blocked while the user has
    // jobs, applications or messages, and carries the Section 5.8 side effects otherwise.
    // The email-changed flag lets the controller decide between the usual flash and the
    // "log the admin out of their own session" path (Section 4.6/6.2 rule 3) - that
    // decision needs HttpServletRequest/Response, which stay in the controller.
    @Transactional
    public UpdateOutcome update(Long id, UserForm form, Long adminId) {
        User user = findById(id);
        boolean self = id.equals(adminId);
        User admin = self ? user : loadAdmin(adminId);

        Role previousRole = user.getRole();
        boolean previousEnabled = user.isEnabled();
        boolean roleChanging = form.getRole() != previousRole;
        boolean statusChanging = form.isEnabled() != previousEnabled;

        if (self && (roleChanging || statusChanging)) {
            throw new BusinessRuleException(SELF_ROLE_OR_STATUS_MESSAGE);
        }
        if (statusChanging && !form.isEnabled()) {
            ensureNotLastActiveAdmin(user);
        }
        if (roleChanging && previousRole == Role.ADMIN) {
            ensureNotLastActiveAdmin(user);
        }
        if (roleChanging) {
            ensureNoDependenciesForRoleChange(id);
        }

        String previousEmail = user.getEmail();
        boolean passwordChanged = form.getNewPassword() != null && !form.getNewPassword().isBlank();

        user.setFullName(form.getFullName());
        user.setEmail(normaliseEmail(form.getEmail()));
        user.setEnabled(form.isEnabled());
        if (roleChanging) {
            applyRoleChange(user, previousRole, form.getRole());
        }
        if (form.getRole() == Role.EMPLOYER) {
            user.setCompanyName(form.getCompanyName());
        }
        if (passwordChanged) {
            user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
        }
        user.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(user);

        activityLogService.log(ActivityType.USER_UPDATED, admin, admin.getFullName() + " updated " + user.getFullName(),
                TargetType.USER, user.getId());

        boolean emailChanged = !previousEmail.equalsIgnoreCase(user.getEmail());
        return new UpdateOutcome(user, passwordChanged, emailChanged);
    }

    // Seeker to another role: the profile (and its resume file, after commit) is
    // removed. To seeker: a fresh, empty profile is created. From employer: the company
    // fields are cleared (Section 5.8). Company name for a role that IS (or becomes)
    // EMPLOYER is set by the caller, once, after this returns.
    private void applyRoleChange(User user, Role previousRole, Role newRole) {
        if (previousRole == Role.JOB_SEEKER) {
            seekerProfileRepository.findByUser_Id(user.getId()).ifPresent(profile -> {
                if (profile.getResumeStoredName() != null) {
                    fileStorageService.deleteAfterCommit(profile.getResumeStoredName());
                }
                seekerProfileRepository.delete(profile);
            });
        }
        if (newRole == Role.JOB_SEEKER) {
            SeekerProfile profile = new SeekerProfile();
            profile.setUser(user);
            profile.setExperienceYears(0);
            seekerProfileRepository.save(profile);
        }
        if (previousRole == Role.EMPLOYER) {
            user.setCompanyName(null);
            user.setCompanyWebsite(null);
            user.setCompanyDescription(null);
        }
        user.setRole(newRole);
    }

    // ---- Activate / deactivate (A-F1, Section 4.6) ----

    @Transactional
    public User toggleStatus(Long id, Long adminId) {
        User user = findById(id);
        if (id.equals(adminId)) {
            throw new BusinessRuleException(SELF_DELETE_OR_DEACTIVATE_MESSAGE);
        }
        boolean activating = !user.isEnabled();
        if (!activating) {
            ensureNotLastActiveAdmin(user);
        }
        User admin = loadAdmin(adminId);
        user.setEnabled(activating);
        user.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(user);

        String description = admin.getFullName() + (activating ? " activated " : " deactivated ") + displayLabel(user);
        activityLogService.log(ActivityType.USER_STATUS_CHANGED, admin, description, TargetType.USER, user.getId());
        return user;
    }

    // ---- Delete (A-F1, Section 5.8) ----

    // Returns the now-deleted user (still a plain in-memory object after
    // userRepository.delete, just no longer backed by a row) so the controller can build
    // its flash message without a second lookup.
    @Transactional
    public User delete(Long id, Long adminId) {
        User user = findById(id);
        if (id.equals(adminId)) {
            throw new BusinessRuleException(SELF_DELETE_OR_DEACTIVATE_MESSAGE);
        }
        ensureNotLastActiveAdmin(user);

        DependencyCounts counts = dependencyCounts(id);
        if (counts.any()) {
            throw new BusinessRuleException(blockedDeleteMessage(counts));
        }

        User admin = loadAdmin(adminId);
        String description = admin.getFullName() + " deleted " + displayLabel(user);

        if (user.getRole() == Role.JOB_SEEKER) {
            seekerProfileRepository.findByUser_Id(id).ifPresent(profile -> {
                if (profile.getResumeStoredName() != null) {
                    fileStorageService.deleteAfterCommit(profile.getResumeStoredName());
                }
                seekerProfileRepository.delete(profile);
            });
        }
        userRepository.delete(user);

        // No target: the user row is gone (Section 5.7 catalogue, USER_DELETED).
        activityLogService.log(ActivityType.USER_DELETED, admin, description, null, null);
        return user;
    }

    public String blockedDeleteMessage(DependencyCounts counts) {
        return "This user has " + counts.applications() + " applications and " + counts.messages()
                + " messages. Deactivate the account instead.";
    }

    // ---- Shared helpers ----

    // true when email belongs to a DIFFERENT user than excludeUserId (null on create,
    // where no user should be excluded). Used by the controller exactly like
    // UserAccountService.emailExists is used by AuthController (Section 7.2).
    public boolean emailInUse(String email, Long excludeUserId) {
        return userRepository.findByEmail(normaliseEmail(email))
                .map(User::getId)
                .filter(existingId -> !existingId.equals(excludeUserId))
                .isPresent();
    }

    // Blocks a role, status or delete change on the only enabled ADMIN account left
    // (Section 1.6 D-12/D-16, 6.2 A-F1 rule 4). A no-op for anyone who is not currently
    // an enabled admin, since disabling an already-disabled account, or any non-admin,
    // never removes the last active admin.
    private void ensureNotLastActiveAdmin(User user) {
        if (user.getRole() != Role.ADMIN || !user.isEnabled()) {
            return;
        }
        long activeAdmins = userRepository.search(null, Role.ADMIN, Boolean.TRUE, Pageable.unpaged()).getTotalElements();
        if (activeAdmins <= 1) {
            throw new BusinessRuleException(LAST_ADMIN_MESSAGE);
        }
    }

    private void ensureNoDependenciesForRoleChange(Long userId) {
        if (dependencyCounts(userId).any()) {
            throw new BusinessRuleException(ROLE_CHANGE_BLOCKED_MESSAGE);
        }
    }

    private User loadAdmin(Long adminId) {
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Logged-in admin no longer exists: " + adminId));
    }

    // "CompanyName (fullName)" for an employer, the plain name otherwise - the target
    // display convention Section 5.7's USER_STATUS_CHANGED/USER_DELETED examples use
    // ("Site Admin deactivated QuickHire Staffing (Suresh Pillai)"), the reverse of the
    // fullName-first ACTOR convention in JobService/ActivityLogService. Create and update
    // messages never use this: Section 6.2 A-F1 says the name in those two is always "the
    // name as saved" (fullName alone), even right after a role change into EMPLOYER.
    // Public so AdminUserController's toggle-status/delete flash messages (built from the
    // User this service hands back) use the exact same wording as the activity log.
    public String displayLabel(User user) {
        if (user.getRole() == Role.EMPLOYER && user.getCompanyName() != null) {
            return user.getCompanyName() + " (" + user.getFullName() + ")";
        }
        return user.getFullName();
    }

    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normaliseQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    // "Any" (missing or unrecognised) falls back to null (Section 7.9 binding rule): the
    // plan gives no exact query values for this filter, so ADMIN/EMPLOYER/JOB_SEEKER (the
    // enum names, as posted by the filter <select>) are the accepted ones.
    private Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // "ACTIVE"/"INACTIVE" (again the plan leaves the exact query value to us); anything
    // else, including missing, means "Any" (Section 7.9 binding rule).
    private Boolean parseStatus(String raw) {
        if ("ACTIVE".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("INACTIVE".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private int parsePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(value, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Section 5.8 delete-confirmation summary: "Jobs: N * Applications: N * Messages: N".
    public record DependencyCounts(long jobs, long applications, long messages) {
        public boolean any() {
            return jobs > 0 || applications > 0 || messages > 0;
        }
    }

    // What AdminUserController needs after a successful update: the saved user (for the
    // "as saved" name in the flash), whether a new password was set (adds the "works
    // immediately" sentence) and whether the email changed (the admin's own row only:
    // triggers the emailChanged logout redirect instead of the usual flash, Section 6.2
    // A-F1 rule 3 / Section 4.6).
    public record UpdateOutcome(User user, boolean passwordChanged, boolean emailChanged) {
    }
}
