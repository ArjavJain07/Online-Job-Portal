package com.jobportal.service;

import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.web.form.SettingsForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The single system_settings row, id 1 (Section 7.5). get() reads it fresh on every call
// - settings are never cached in memory, so a rolled-back save (or a rolled-back test)
// can never leave the application running on values the database does not have.
@Service
public class SettingsService {

    private static final long SETTINGS_ID = 1L;

    private final SystemSettingsRepository systemSettingsRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public SettingsService(SystemSettingsRepository systemSettingsRepository, ActivityLogService activityLogService,
            Clock clock) {
        this.systemSettingsRepository = systemSettingsRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    // Callers treat the returned entity as read-only; only save() changes it.
    public SystemSettings get() {
        return systemSettingsRepository.findById(SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "System settings row (id 1) is missing. Restart the application to let DataSeeder create it."));
    }

    // Called by DataSeeder on every startup: creates the id-1 row with the 7.5 defaults
    // the first time the application ever runs, and does nothing on later starts.
    // updatedAt/updatedBy stay null until an admin actually saves the settings form.
    @Transactional
    public void ensureDefaults() {
        if (systemSettingsRepository.existsById(SETTINGS_ID)) {
            return;
        }
        SystemSettings settings = new SystemSettings();
        settings.setId(SETTINGS_ID);
        systemSettingsRepository.save(settings);
    }

    // Copies the form (already validated by the controller) into the settings row, saves
    // it, and logs SETTINGS_UPDATED naming only the fields that actually changed.
    @Transactional
    public void save(SettingsForm form, User admin) {
        SystemSettings settings = get();
        List<String> changedFields = new ArrayList<>();
        String allowedTypes = normaliseAllowedTypes(form.getAllowedResumeTypes());

        applyChange(changedFields, "siteName", settings.getSiteName(), form.getSiteName(), settings::setSiteName);
        applyChange(changedFields, "announcement", settings.getAnnouncement(), form.getAnnouncement(),
                settings::setAnnouncement);
        applyChange(changedFields, "seekerRegistrationOpen", settings.isSeekerRegistrationOpen(),
                form.isSeekerRegistrationOpen(), settings::setSeekerRegistrationOpen);
        applyChange(changedFields, "employerRegistrationOpen", settings.isEmployerRegistrationOpen(),
                form.isEmployerRegistrationOpen(), settings::setEmployerRegistrationOpen);
        applyChange(changedFields, "jobApprovalRequired", settings.isJobApprovalRequired(),
                form.isJobApprovalRequired(), settings::setJobApprovalRequired);
        applyChange(changedFields, "maxActiveJobsPerEmployer", settings.getMaxActiveJobsPerEmployer(),
                form.getMaxActiveJobsPerEmployer(), settings::setMaxActiveJobsPerEmployer);
        applyChange(changedFields, "maxResumeSizeMb", settings.getMaxResumeSizeMb(), form.getMaxResumeSizeMb(),
                settings::setMaxResumeSizeMb);
        applyChange(changedFields, "allowedResumeTypes", settings.getAllowedResumeTypes(), allowedTypes,
                settings::setAllowedResumeTypes);
        applyChange(changedFields, "pageSize", settings.getPageSize(), form.getPageSize(), settings::setPageSize);
        applyChange(changedFields, "feedRefreshSeconds", settings.getFeedRefreshSeconds(), form.getFeedRefreshSeconds(),
                settings::setFeedRefreshSeconds);

        settings.setUpdatedAt(LocalDateTime.now(clock));
        settings.setUpdatedBy(admin.getFullName());
        systemSettingsRepository.save(settings);

        String description = admin.getFullName() + " updated settings"
                + (changedFields.isEmpty() ? "" : ": " + String.join(", ", changedFields));
        activityLogService.log(ActivityType.SETTINGS_UPDATED, admin, description, TargetType.SETTINGS, settings.getId());
    }

    // The checkboxes come in as typed by the form; the stored value is always a
    // lower-case, comma-separated list ("pdf,doc,docx").
    private String normaliseAllowedTypes(List<String> types) {
        List<String> lower = new ArrayList<>();
        if (types != null) {
            for (String type : types) {
                if (type != null && !type.isBlank()) {
                    lower.add(type.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return String.join(",", lower);
    }

    // Records the field name when the new value differs from the stored one, then
    // applies it - one place for both jobs so the diff list can never drift from what
    // was actually saved.
    private <T> void applyChange(List<String> changedFields, String fieldName, T oldValue, T newValue,
            Consumer<T> setter) {
        if (!Objects.equals(oldValue, newValue)) {
            changedFields.add(fieldName);
        }
        setter.accept(newValue);
    }
}
