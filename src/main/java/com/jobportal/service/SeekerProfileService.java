package com.jobportal.service;

import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.ProfileCompleteness;
import com.jobportal.dto.StoredFile;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.web.form.SeekerProfileForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// Seeker profile: personal fields plus the one profile resume (Section 6.4 S-F4/S-D3,
// Section 6.5.2). fullName and email live on User; every other field lives on
// SeekerProfile, one row per seeker created empty at registration (decision D-6,
// UserAccountService.registerSeeker) - loadProfile therefore only ever fails if that
// invariant was somehow broken, the same "should never happen" case
// EmployerProfileService.findById guards for the User row.
@Service
public class SeekerProfileService {

    private final UserRepository userRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final FileStorageService fileStorageService;
    private final ActivityLogService activityLogService;
    private final SkillService skillService;
    private final Clock clock;

    public SeekerProfileService(UserRepository userRepository, SeekerProfileRepository seekerProfileRepository,
            FileStorageService fileStorageService, ActivityLogService activityLogService,
            SkillService skillService, Clock clock) {
        this.userRepository = userRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.fileStorageService = fileStorageService;
        this.activityLogService = activityLogService;
        this.skillService = skillService;
        this.clock = clock;
    }

    public User loadUser(Long seekerId) {
        return userRepository.findById(seekerId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists: " + seekerId));
    }

    public SeekerProfile loadProfile(Long seekerId) {
        return seekerProfileRepository.findByUser_Id(seekerId)
                .orElseThrow(() -> new IllegalStateException("Seeker profile missing for user: " + seekerId));
    }

    // Same duplicate-email check every other form with an editable email uses (Section
    // 7.2 pattern, mirrors EmployerProfileService.emailInUse): true only when the email
    // belongs to a DIFFERENT user than seekerId, so saving the form with its own
    // unchanged email never trips this.
    public boolean emailInUse(String email, Long seekerId) {
        return userRepository.findByEmail(normaliseEmail(email))
                .map(User::getId)
                .filter(existingId -> !existingId.equals(seekerId))
                .isPresent();
    }

    // AC-S-F4-1/AC-S-F4-3: saves the name/email (on User) and every profile field (on
    // SeekerProfile), normalising skills the same way JobForm.skills is normalised
    // (Section 7.8). Whether the email changed is reported back instead of acted on here -
    // forcing a logout needs the HttpServletRequest/Response the controller has (Section
    // 6.4 S-F4 business rule "Email change forces a new login").
    @Transactional
    public UpdateOutcome save(Long seekerId, SeekerProfileForm form) {
        User user = loadUser(seekerId);
        SeekerProfile profile = loadProfile(seekerId);
        String previousEmail = user.getEmail();
        LocalDateTime now = LocalDateTime.now(clock);

        user.setFullName(form.getFullName());
        user.setEmail(normaliseEmail(form.getEmail()));
        user.setUpdatedAt(now);
        userRepository.save(user);

        profile.setPhone(form.getPhone());
        profile.setLocation(form.getLocation());
        profile.setHeadline(form.getHeadline());
        profile.assignSkills(skillService.resolve(form.getSkills()));
        profile.setExperienceYears(form.getExperienceYears());
        profile.setPreferredJobType(form.getPreferredJobType());
        profile.setEducation(form.getEducation());
        profile.setAbout(form.getAbout());
        profile.setUpdatedAt(now);
        seekerProfileRepository.save(profile);

        activityLogService.log(ActivityType.PROFILE_UPDATED, user, user.getFullName() + " updated their profile",
                TargetType.USER, user.getId());

        boolean emailChanged = !previousEmail.equalsIgnoreCase(user.getEmail());
        return new UpdateOutcome(user, emailChanged);
    }

    // AC-S-F4-2: validates and stores the new file first (FileValidationException left
    // uncaught here - the upload controller catches it and attaches it as a field error,
    // Section 7.3), then swaps it onto the profile and deletes the old one only after the
    // transaction commits, so a rolled-back save never leaves the profile pointing at a
    // deleted file (Section 7.4 "Copies"/"Deleting", 5.8).
    @Transactional
    public SeekerProfile uploadResume(Long seekerId, MultipartFile file) {
        SeekerProfile profile = loadProfile(seekerId);
        StoredFile stored = fileStorageService.store(file);
        String previousStoredName = profile.getResumeStoredName();

        profile.setResumeStoredName(stored.storedName());
        profile.setResumeOriginalName(stored.originalName());
        profile.setResumeContentType(stored.contentType());
        profile.setResumeSizeBytes(stored.sizeBytes());
        profile.setResumeUploadedAt(LocalDateTime.now(clock));
        profile.setUpdatedAt(LocalDateTime.now(clock));
        seekerProfileRepository.save(profile);

        if (previousStoredName != null) {
            fileStorageService.deleteAfterCommit(previousStoredName);
        }

        User user = loadUser(seekerId);
        activityLogService.log(ActivityType.RESUME_UPLOADED, user, user.getFullName() + " uploaded a resume",
                TargetType.USER, user.getId());
        return profile;
    }

    // AC-S-F4 output "Resume removed...": clears the five resume columns and deletes the
    // file after commit. Deliberately NOT logged (11.3 contract item 5 lists "profile
    // resume removal" among the events kept out of the activity feed, alongside internal
    // notes, read markers and view counts).
    @Transactional
    public SeekerProfile removeResume(Long seekerId) {
        SeekerProfile profile = loadProfile(seekerId);
        String storedName = profile.getResumeStoredName();
        if (storedName != null) {
            fileStorageService.deleteAfterCommit(storedName);
        }
        profile.setResumeStoredName(null);
        profile.setResumeOriginalName(null);
        profile.setResumeContentType(null);
        profile.setResumeSizeBytes(null);
        profile.setResumeUploadedAt(null);
        profile.setUpdatedAt(LocalDateTime.now(clock));
        seekerProfileRepository.save(profile);
        return profile;
    }

    // Completeness bar (Section 6.4 S-F4 "Completeness bar", weights sum to 100): adds up
    // the weight of every filled field and returns the sentence for the FIRST missing one
    // in this same weight order (highest weight first), or a null hint once every field is
    // filled (the bar is then hidden, Section 6.4 DASH-S). Only the two hints for skills
    // and headline are given verbatim by the plan (AC-S-F4-1, AC-S-D3-1); the other five
    // are this class's own wording, kept in the same plain, encouraging tone.
    public ProfileCompleteness completeness(SeekerProfile profile) {
        int percent = 0;
        String hint = null;

        if (profile.getResumeStoredName() != null) {
            percent += 30;
        } else if (hint == null) {
            hint = "Upload your resume so employers can review it.";
        }
        if (!profile.getSkills().isEmpty()) {
            percent += 20;
        } else if (hint == null) {
            hint = "Add your skills to get better job recommendations.";
        }
        if (isFilled(profile.getHeadline())) {
            percent += 10;
        } else if (hint == null) {
            hint = "Add a headline so employers know what you do.";
        }
        if (isFilled(profile.getLocation())) {
            percent += 10;
        } else if (hint == null) {
            hint = "Add your location so employers know where you're based.";
        }
        if (isFilled(profile.getPhone())) {
            percent += 10;
        } else if (hint == null) {
            hint = "Add a phone number so employers can reach you.";
        }
        if (isFilled(profile.getEducation())) {
            percent += 10;
        } else if (hint == null) {
            hint = "Add your education so employers know your background.";
        }
        if (isFilled(profile.getAbout())) {
            percent += 10;
        } else if (hint == null) {
            hint = "Write a short summary about yourself.";
        }

        return new ProfileCompleteness(percent, percent == 100 ? null : hint);
    }

    private boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }

    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // What SeekerProfileController needs after a successful save: the saved user (for
    // pre-filling the form again) and whether the email changed (triggers the
    // emailChanged logout redirect instead of the usual flash, same pattern as
    // EmployerProfileService.UpdateOutcome, Section 6.4 S-F4).
    public record UpdateOutcome(User user, boolean emailChanged) {
    }
}
