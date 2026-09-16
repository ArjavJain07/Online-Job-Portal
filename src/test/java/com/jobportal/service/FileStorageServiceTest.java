package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobportal.config.AppProperties;
import com.jobportal.domain.SystemSettings;
import com.jobportal.dto.StoredFile;
import com.jobportal.exception.FileValidationException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.support.TestFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Unit tests for FileStorageService (Section 7.4), built by hand with a mocked
// SettingsService and a @TempDir instead of a Spring context (Section 12.1).
class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        SystemSettings settings = new SystemSettings(); // defaults: pdf,doc,docx and 2 MB (Section 7.5)
        when(settingsService.get()).thenReturn(settings);

        AppProperties appProperties = new AppProperties(tempDir.toString(), null, null);
        fileStorageService = new FileStorageService(appProperties, settingsService);
        fileStorageService.init(); // @PostConstruct is not run automatically without Spring
    }

    @Test
    void rejectsWrongExtension() {
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.exe", "application/octet-stream",
                TestFiles.pdfBytes());

        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("Only PDF, DOC or DOCX files are allowed.");
    }

    @Test
    void rejectsFakeContent() {
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf",
                TestFiles.fakeContent());

        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("The file content doesn't match its extension.");
    }

    @Test
    void rejectsEmpty() {
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("The file is empty.");
    }

    @Test
    void rejectsTooLarge() {
        byte[] oversized = TestFiles.pdfBytesOfSize(2 * 1024 * 1024 + 1); // just over the 2 MB default
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> fileStorageService.store(file))
                .isInstanceOf(FileValidationException.class)
                .hasMessage("File is larger than 2 MB.");
    }

    @Test
    void storesUnderUuidName() {
        MockMultipartFile file = new MockMultipartFile("resumeFile", "My Resume.pdf", "application/pdf",
                TestFiles.pdfBytes());

        StoredFile stored = fileStorageService.store(file);

        assertThat(stored.storedName())
                .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.pdf");
        assertThat(stored.originalName()).isEqualTo("My Resume.pdf");
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(Files.exists(tempDir.resolve("resumes").resolve(stored.storedName()))).isTrue();
    }

    @Test
    void copyCreatesIndependentFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("resumeFile", "resume.pdf", "application/pdf",
                TestFiles.pdfBytes());
        StoredFile original = fileStorageService.store(file);

        StoredFile copy = fileStorageService.copy(original.storedName());

        Path originalPath = tempDir.resolve("resumes").resolve(original.storedName());
        Path copyPath = tempDir.resolve("resumes").resolve(copy.storedName());
        assertThat(copy.storedName()).isNotEqualTo(original.storedName());
        assertThat(Files.exists(originalPath)).isTrue();
        assertThat(Files.exists(copyPath)).isTrue();
        assertThat(Files.readAllBytes(copyPath)).isEqualTo(Files.readAllBytes(originalPath));

        // Independent: deleting the original never touches the copy (Section 7.4).
        fileStorageService.deleteNow(original.storedName());
        assertThat(Files.exists(originalPath)).isFalse();
        assertThat(Files.exists(copyPath)).isTrue();
    }

    // Simulates a request's transaction by hand (Section 12.1: "triggering the transaction
    // synchronisation manually"): a deletion registered with deleteAfterCommit must survive
    // until something actually runs afterCommit, and never happen on its own.
    @Test
    void deleteAfterCommitRunsOnlyOnCommit() {
        StoredFile committed = fileStorageService.store(
                new MockMultipartFile("resumeFile", "a.pdf", "application/pdf", TestFiles.pdfBytes()));
        StoredFile neverCommitted = fileStorageService.store(
                new MockMultipartFile("resumeFile", "b.pdf", "application/pdf", TestFiles.pdfBytes()));
        Path committedPath = tempDir.resolve("resumes").resolve(committed.storedName());
        Path neverCommittedPath = tempDir.resolve("resumes").resolve(neverCommitted.storedName());

        TransactionSynchronizationManager.initSynchronization();
        try {
            fileStorageService.deleteAfterCommit(committed.storedName());
            fileStorageService.deleteAfterCommit(neverCommitted.storedName());

            // Registering the deletion must not delete anything by itself.
            assertThat(Files.exists(committedPath)).isTrue();
            assertThat(Files.exists(neverCommittedPath)).isTrue();

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(2);
            synchronizations.get(0).afterCommit(); // as if only the first transaction committed
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(Files.exists(committedPath)).isFalse();
        assertThat(Files.exists(neverCommittedPath)).isTrue(); // never committed, so never deleted
    }

    @Test
    void deleteAfterCommitDeletesImmediatelyOutsideATransaction() {
        StoredFile stored = fileStorageService.store(
                new MockMultipartFile("resumeFile", "a.pdf", "application/pdf", TestFiles.pdfBytes()));
        Path path = tempDir.resolve("resumes").resolve(stored.storedName());

        fileStorageService.deleteAfterCommit(stored.storedName());

        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void pathTraversalBlocked() {
        assertThatThrownBy(() -> fileStorageService.load("../secret.pdf"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> fileStorageService.load("../../etc/passwd"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingFileGivesResourceNotFound() {
        assertThatThrownBy(() -> fileStorageService.load("does-not-exist.pdf"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
