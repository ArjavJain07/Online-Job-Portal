package com.jobportal.service;

import com.jobportal.config.AppProperties;
import com.jobportal.domain.SystemSettings;
import com.jobportal.dto.StoredFile;
import com.jobportal.exception.FileValidationException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.util.FileNames;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

// Stores and serves resume files on local disk (Section 7.4). Every other service
// touches files only through this class (11.3 item 7): it is the only place a stored
// name is turned into a path, and the only place upload rules are enforced.
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] DOC_SIGNATURE =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] DOCX_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    private final AppProperties appProperties;
    private final SettingsService settingsService;

    private Path resumesDir;

    public FileStorageService(AppProperties appProperties, SettingsService settingsService) {
        this.appProperties = appProperties;
        this.settingsService = settingsService;
    }

    // Resolves the upload root once at startup, creates the resumes subfolder, and
    // proves it is writable by writing and deleting a test file (Section 7.4).
    @PostConstruct
    public void init() {
        Path uploadRoot = Paths.get(appProperties.uploadDir()).toAbsolutePath().normalize();
        resumesDir = uploadRoot.resolve("resumes");
        try {
            Files.createDirectories(resumesDir);
            Path testFile = resumesDir.resolve(".startup-check");
            Files.writeString(testFile, "ok");
            Files.delete(testFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Upload folder " + uploadRoot + " is not writable. Set app.upload-dir to a writable folder.", e);
        }
        log.info("Upload folder: {}", uploadRoot);
    }

    // Validates a user-uploaded resume, then stores it under a new UUID name (S-F2, S-F4).
    public StoredFile store(MultipartFile file) {
        String originalName = file == null ? null : file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new FileValidationException("Please choose a file to upload.");
        }
        if (file.isEmpty()) {
            throw new FileValidationException("The file is empty.");
        }

        SystemSettings settings = settingsService.get();
        List<String> allowedTypes = parseAllowedTypes(settings.getAllowedResumeTypes());
        String extension = extensionOf(originalName);
        if (!allowedTypes.contains(extension)) {
            throw new FileValidationException(allowedTypesMessage(allowedTypes));
        }

        long maxBytes = settings.getMaxResumeSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new FileValidationException("File is larger than " + settings.getMaxResumeSizeMb() + " MB.");
        }

        byte[] content = readBytes(file);
        if (!contentMatchesExtension(content, extension)) {
            throw new FileValidationException("The file content doesn't match its extension.");
        }

        return writeNewFile(content, originalName, extension);
    }

    // Stores a demo file bundled with the app, skipping the upload-form validation
    // (DemoDataLoader only, Section 7.4). The content type is derived from originalName.
    public StoredFile storeSeedFile(InputStream in, String originalName) {
        byte[] content;
        try {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writeNewFile(content, originalName, extensionOf(originalName));
    }

    // Makes an independent copy of an already-stored file under a new UUID name, so an
    // application's resume never changes if the seeker later replaces their profile
    // resume (Section 7.4).
    public StoredFile copy(String storedName) {
        Path source = resolve(storedName);
        if (!Files.exists(source)) {
            throw new ResourceNotFoundException("The resume file could not be found.");
        }
        String extension = extensionOf(storedName);
        String newStoredName = UUID.randomUUID() + "." + extension;
        Path target = resumesDir.resolve(newStoredName);
        try {
            Files.copy(source, target);
            long size = Files.size(target);
            return new StoredFile(newStoredName, storedName, contentTypeFor(extension), size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Loads a stored file so it can be served for inline viewing or download.
    public Resource load(String storedName) {
        Path path = resolve(storedName);
        Resource resource;
        try {
            resource = new UrlResource(path.toUri());
        } catch (MalformedURLException e) {
            throw new ResourceNotFoundException("The resume file could not be found.");
        }
        if (!resource.exists() || !resource.isReadable()) {
            throw new ResourceNotFoundException("The resume file could not be found.");
        }
        return resource;
    }

    // Registers the file for deletion only after the current transaction commits, so a
    // rollback never leaves a database row pointing at a deleted file (Section 5.8).
    public void deleteAfterCommit(String storedName) {
        if (storedName == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteNow(storedName);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteNow(storedName);
            }
        });
    }

    // Deletes a file immediately; used to clean up after a failed insert (Section 7.4).
    public void deleteNow(String storedName) {
        if (storedName == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", storedName, e.getMessage());
        }
    }

    // ---- helpers ----

    private StoredFile writeNewFile(byte[] content, String originalName, String extension) {
        String storedName = UUID.randomUUID() + "." + extension;
        Path target = resumesDir.resolve(storedName);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StoredFile(storedName, FileNames.sanitise(originalName), contentTypeFor(extension), content.length);
    }

    // Resolves a stored name to a path inside the resumes folder. A name built to escape
    // it (path traversal) is refused the same way a missing file is (Section 7.4).
    private Path resolve(String storedName) {
        Path resolved = resumesDir.resolve(storedName).normalize();
        if (!resolved.startsWith(resumesDir)) {
            throw new ResourceNotFoundException("The resume file could not be found.");
        }
        return resolved;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<String> parseAllowedTypes(String csv) {
        List<String> types = new ArrayList<>();
        for (String type : csv.split(",")) {
            String trimmed = type.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types;
    }

    private static String allowedTypesMessage(List<String> allowedTypes) {
        List<String> upper = new ArrayList<>();
        for (String type : allowedTypes) {
            upper.add(type.toUpperCase(Locale.ROOT));
        }
        String joined = upper.size() == 1
                ? upper.get(0)
                : String.join(", ", upper.subList(0, upper.size() - 1)) + " or " + upper.get(upper.size() - 1);
        return "Only " + joined + " files are allowed.";
    }

    private static boolean contentMatchesExtension(byte[] content, String extension) {
        return switch (extension) {
            case "pdf" -> startsWith(content, PDF_SIGNATURE);
            case "doc" -> startsWith(content, DOC_SIGNATURE);
            case "docx" -> startsWith(content, DOCX_SIGNATURE);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String contentTypeFor(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
    }
}
