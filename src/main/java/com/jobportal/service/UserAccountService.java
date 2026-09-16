package com.jobportal.service;

import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.web.form.ChangePasswordForm;
import com.jobportal.web.form.RegisterEmployerForm;
import com.jobportal.web.form.RegisterSeekerForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Registration (seeker and employer, Section 4.3/6.1 P-3) and change-password (Section
// 4.8/6.1 P-5) logic; controllers stay thin. The plan splits this across a
// RegistrationService and UserService.changePassword (Section 9), but this slice's file
// ownership is a single service, so both live here - see the class-level note in the
// milestone summary for the other agents building UserService (admin create/update/
// toggle-status/delete, Milestone M3): that class must not duplicate the registration or
// password-change methods below.
@Service
public class UserAccountService {

    public static final String DUPLICATE_EMAIL_MESSAGE = "An account with this email already exists.";

    private final UserRepository userRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final ActivityLogService activityLogService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserAccountService(UserRepository userRepository, SeekerProfileRepository seekerProfileRepository,
            ActivityLogService activityLogService, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.activityLogService = activityLogService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // Called by the controller before saving, so the duplicate message can be attached to
    // the email field with result.rejectValue (Section 7.3). The unique column on
    // users.email is the safety net for the rare race between this check and the save
    // (rule D-26): saveAndFlush below surfaces such a violation immediately, and it falls
    // through to GlobalExceptionHandler's generic DataIntegrityViolationException message.
    public boolean emailExists(String email) {
        return userRepository.findByEmail(normaliseEmail(email)).isPresent();
    }

    // AC-P3-1: creates the User (enabled) and an empty SeekerProfile, logs
    // USER_REGISTERED. No automatic login (4.3): the controller redirects to
    // /login?registered.
    @Transactional
    public User registerSeeker(RegisterSeekerForm form) {
        User user = buildUser(form, Role.JOB_SEEKER);
        userRepository.saveAndFlush(user);

        SeekerProfile profile = new SeekerProfile();
        profile.setUser(user);
        profile.setExperienceYears(0);
        seekerProfileRepository.save(profile);

        activityLogService.log(ActivityType.USER_REGISTERED, user,
                user.getFullName() + " registered as a job seeker", TargetType.USER, user.getId());
        return user;
    }

    // Same as registerSeeker, but for an employer: no SeekerProfile, company fields
    // copied from the form instead.
    @Transactional
    public User registerEmployer(RegisterEmployerForm form) {
        User user = buildUser(form, Role.EMPLOYER);
        user.setCompanyName(form.getCompanyName());
        user.setCompanyWebsite(form.getCompanyWebsite());
        userRepository.saveAndFlush(user);

        activityLogService.log(ActivityType.USER_REGISTERED, user,
                user.getFullName() + " registered as an employer", TargetType.USER, user.getId());
        return user;
    }

    // AC-P5-1: checks the current password against the stored hash, requires the new one
    // to be different, then hashes and saves it. The user stays logged in (6.1 P-5); the
    // session still holds the old AppUserDetails, but that copy already had its password
    // hash erased after login (4.8) and is never compared again until the next login.
    @Transactional
    public void changePassword(Long userId, ChangePasswordForm form) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists: " + userId));

        if (!passwordEncoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("Current password is incorrect.");
        }
        if (passwordEncoder.matches(form.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("New password must be different from the current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(user);

        activityLogService.log(ActivityType.PASSWORD_CHANGED, user,
                user.getFullName() + " changed their password", TargetType.USER, user.getId());
    }

    // Shared by both registration paths: full name, normalised email, hashed password,
    // enabled, createdAt from the Clock (11.3 item 5). The role-specific fields are set
    // by the caller.
    private User buildUser(RegisterSeekerForm form, Role role) {
        User user = new User();
        user.setFullName(form.getFullName());
        user.setEmail(normaliseEmail(form.getEmail()));
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now(clock));
        return user;
    }

    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
