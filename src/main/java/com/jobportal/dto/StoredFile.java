package com.jobportal.dto;

// What FileStorageService hands back after saving a file (Section 7.4): the name it was
// stored under (a random UUID plus extension, used to look it up later), the original
// file name the user uploaded (shown in the UI), the content type, and the size in bytes.
public record StoredFile(String storedName, String originalName, String contentType, long sizeBytes) {
}
