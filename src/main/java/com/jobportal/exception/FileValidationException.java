package com.jobportal.exception;

// A resume upload failed validation (wrong type, too large, empty, content does not match
// its extension - Section 7.4). Extends BusinessRuleException but upload controllers catch
// it themselves and attach the message to the resumeFile field instead of flashing it.
public class FileValidationException extends BusinessRuleException {

    public FileValidationException(String message) {
        super(message);
    }
}
