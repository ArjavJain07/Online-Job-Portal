package com.jobportal.service;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.UserRepository;
import com.jobportal.web.form.EmployerProfileForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Company profile (Section 6.3 EP). Company fields live directly on User (decision D-6:
// there is no separate EmployerProfile entity, only job seekers get a profile row), so
// this service is a small, self-contained read/update wrapper around UserRepository -
// UserAccountService (registration, change-password) and UserService (admin user
// management) are both owned by other slices and neither one touches this route.
@Service
public class EmployerProfileService {

    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public EmployerProfileService(UserRepository userRepository, ActivityLogService activityLogService, Clock clock) {
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    public User findById(Long employerId) {
        return userRepository.findById(employerId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists: " + employerId));
    }

    // Same duplicate-email check every other form with an editable email uses (Section
    // 7.2 pattern): true when the email belongs to a DIFFERENT user than employerId, so
    // saving the form with its own unchanged email never trips this.
    public boolean emailInUse(String email, Long employerId) {
        return userRepository.findByEmail(normaliseEmail(email))
                .map(User::getId)
                .filter(existingId -> !existingId.equals(employerId))
                .isPresent();
    }

    // AC-EP-1: saves the contact name, email and company fields, logs PROFILE_UPDATED.
    // Whether the email changed is reported back instead of acted on here - forcing a
    // logout needs the HttpServletRequest/Response the controller has, the same split
    // UserService.update uses for the admin's own row (Section 6.2 A-F1 rule 3).
    @Transactional
    public UpdateOutcome save(Long employerId, EmployerProfileForm form) {
        User employer = findById(employerId);
        String previousEmail = employer.getEmail();

        employer.setFullName(form.getFullName());
        employer.setEmail(normaliseEmail(form.getEmail()));
        employer.setCompanyName(form.getCompanyName());
        employer.setCompanyWebsite(form.getCompanyWebsite());
        employer.setCompanyDescription(form.getCompanyDescription());
        employer.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(employer);

        activityLogService.log(ActivityType.PROFILE_UPDATED, employer, employer.getFullName() + " updated their profile",
                TargetType.USER, employer.getId());

        boolean emailChanged = !previousEmail.equalsIgnoreCase(employer.getEmail());
        return new UpdateOutcome(employer, emailChanged);
    }

    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // What EmployerProfileController needs after a successful save: the saved user (for
    // pre-filling the form again) and whether the email changed (triggers the
    // emailChanged logout redirect instead of the usual flash, Section 6.3 EP).
    public record UpdateOutcome(User employer, boolean emailChanged) {
    }
}
