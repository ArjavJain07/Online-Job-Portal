package com.jobportal.web.form;

import org.springframework.web.multipart.MultipartFile;

// Profile resume upload/replace form (Section 6.4 S-F4, POST /seeker/profile/resume). No
// Bean Validation annotations: every rule (must be chosen, not empty, allowed type, size,
// content matches extension) is enforced by FileStorageService.store() against the current
// settings (Section 7.4), and SeekerProfileController turns the FileValidationException it
// throws into a field error on "resumeFile" (Section 7.3 table: "Field error on
// resumeFile"), the same field name S-F2's ApplicationForm.resumeFile uses so the messages
// and the test helper (12.1: MockMultipartFile("resumeFile", ...)) stay consistent across
// every upload form in the app.
public class ResumeUploadForm {

    private MultipartFile resumeFile;

    public MultipartFile getResumeFile() {
        return resumeFile;
    }

    public void setResumeFile(MultipartFile resumeFile) {
        this.resumeFile = resumeFile;
    }
}
