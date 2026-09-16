package com.jobportal.web.support;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// Builds the download response for a stored resume file (Section 7.4). PDF opens inline
// in the browser tab; DOC and DOCX are offered as an attachment since browsers cannot
// render them. The "nosniff" header itself comes from Spring Security's default headers.
public final class FileResponses {

    private FileResponses() {
    }

    public static ResponseEntity<Resource> serve(Resource resource, String originalName, String contentType) {
        ContentDisposition disposition = MediaType.APPLICATION_PDF_VALUE.equals(contentType)
                ? ContentDisposition.inline().filename(originalName, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(originalName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}
