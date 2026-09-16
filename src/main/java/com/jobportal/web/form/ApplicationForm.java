package com.jobportal.web.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

// Job application form (Section 6.4 S-F2, field table). resumeFile carries no Bean
// Validation annotations of its own: whether it is required at all depends on
// resumeChoice (a cross-field rule Bean Validation cannot see), and its type/size/content
// rules come from SettingsService (Section 7.4) - both need data Bean Validation cannot
// reach, the same reasoning JobForm's class comment gives for its deadline range. So both
// are checked in the controller/JobApplicationService/FileStorageService instead and
// reported as a field error on resumeFile or resumeChoice (Section 7.3).
public class ApplicationForm {

    // Also used for the PROFILE-choice-without-a-profile-resume business rule, checked in
    // the controller/service rather than here, so the same wording serves both cases.
    public static final String RESUME_CHOICE_MESSAGE = "Please upload a resume or choose your profile resume.";

    // Form value only, never stored - so the enum freeze of Section 5.4 does not apply
    // here (Section 6.4 S-F2 field table).
    public enum ResumeChoice {
        PROFILE, UPLOAD
    }

    @NotNull(message = RESUME_CHOICE_MESSAGE)
    private ResumeChoice resumeChoice;

    private MultipartFile resumeFile;

    @Size(max = 3000, message = "Cover letter must be 3000 characters or fewer.")
    private String coverLetter;

    // Shown only for an upload (Section 6.4 S-F2 screen); ticked by default on the GET
    // page when the profile has no resume yet - that default is set by the controller,
    // not here, since it needs to know whether a profile resume exists.
    private boolean saveToProfile;

    public ResumeChoice getResumeChoice() {
        return resumeChoice;
    }

    public void setResumeChoice(ResumeChoice resumeChoice) {
        this.resumeChoice = resumeChoice;
    }

    public MultipartFile getResumeFile() {
        return resumeFile;
    }

    public void setResumeFile(MultipartFile resumeFile) {
        this.resumeFile = resumeFile;
    }

    public String getCoverLetter() {
        return coverLetter;
    }

    public void setCoverLetter(String coverLetter) {
        this.coverLetter = coverLetter;
    }

    public boolean isSaveToProfile() {
        return saveToProfile;
    }

    public void setSaveToProfile(boolean saveToProfile) {
        this.saveToProfile = saveToProfile;
    }
}
