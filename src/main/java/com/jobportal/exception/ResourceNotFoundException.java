package com.jobportal.exception;

// Thrown when a requested record does not exist, or exists but belongs to someone else
// (Section 4.5: ownership queries return "not found" either way). GlobalExceptionHandler
// turns this into the 404 page.
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
