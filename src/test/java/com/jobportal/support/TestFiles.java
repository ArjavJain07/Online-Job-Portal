package com.jobportal.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Byte content for upload tests (Section 12.1 rule: "Uploads use multipart(...).file(new
// MockMultipartFile("resumeFile", "cv.pdf", "application/pdf", TestFiles.pdfBytes()))").
// Mirrors the magic-byte signatures FileStorageService itself checks (Section 7.4), plus a
// file whose extension does not match its content and an oversized-file generator.
public final class TestFiles {

    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] DOC_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] DOCX_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    private TestFiles() {
    }

    public static byte[] pdfBytes() {
        return withSignature(PDF_SIGNATURE);
    }

    public static byte[] docBytes() {
        return withSignature(DOC_SIGNATURE);
    }

    public static byte[] docxBytes() {
        return withSignature(DOCX_SIGNATURE);
    }

    // Looks like a resume by name but is not one (S-F2, G-8): proves the magic-byte check,
    // not just the file extension, decides whether an upload is accepted.
    public static byte[] fakeContent() {
        return "This is plain text, not a real PDF, DOC or DOCX file.".getBytes(StandardCharsets.UTF_8);
    }

    // A valid PDF padded to at least sizeBytes, for the "too large" cases.
    public static byte[] pdfBytesOfSize(int sizeBytes) {
        byte[] content = new byte[Math.max(sizeBytes, PDF_SIGNATURE.length)];
        System.arraycopy(PDF_SIGNATURE, 0, content, 0, PDF_SIGNATURE.length);
        Arrays.fill(content, PDF_SIGNATURE.length, content.length, (byte) 'x');
        return content;
    }

    private static byte[] withSignature(byte[] signature) {
        byte[] filler = "test resume content".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[signature.length + filler.length];
        System.arraycopy(signature, 0, content, 0, signature.length);
        System.arraycopy(filler, 0, content, signature.length, filler.length);
        return content;
    }
}
