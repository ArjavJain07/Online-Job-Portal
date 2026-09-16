package com.jobportal.exception;

// Thrown by a service when a business rule refuses an action (for example, an employer
// already at their active-job limit). Form controllers catch it and show a field or form
// error; a simple POST button lets it reach GlobalExceptionHandler, which shows it as an
// "error" flash and redirects back (Section 7.2, 7.3).
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
