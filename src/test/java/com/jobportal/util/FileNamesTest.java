package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Unit tests for FileNames.sanitise (Section 7.4): only ever used for the display name
// shown back to the user, never for the file's actual path on disk.
class FileNamesTest {

    @Test
    void pathPartsAreRemovedKeepingOnlyTheLastSegment() {
        assertThat(FileNames.sanitise("folder/sub/resume.pdf")).isEqualTo("resume.pdf");
    }

    @Test
    void backslashPathsAreAlsoStripped() {
        assertThat(FileNames.sanitise("C:\\Users\\me\\My Resume.pdf")).isEqualTo("My Resume.pdf");
    }

    @Test
    void doubleQuotesAreRemoved() {
        assertThat(FileNames.sanitise("My \"Final\" Resume.pdf")).isEqualTo("My Final Resume.pdf");
    }

    @Test
    void controlCharactersAreRemoved() {
        String withControlChar = "resume" + '\u0007' + '\u0000' + ".pdf";
        assertThat(FileNames.sanitise(withControlChar)).isEqualTo("resume.pdf");
    }

    @Test
    void resultIsCutTo150Characters() {
        String longName = "a".repeat(200) + ".pdf";
        String result = FileNames.sanitise(longName);
        assertThat(result).hasSize(150);
        assertThat(result).isEqualTo("a".repeat(150));
    }

    @Test
    void nameOfExactly150CharactersIsNotCut() {
        String name = "a".repeat(150);
        assertThat(FileNames.sanitise(name)).isEqualTo(name);
    }

    @Test
    void nullNameGivesEmptyString() {
        assertThat(FileNames.sanitise(null)).isEmpty();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(FileNames.sanitise("  resume.pdf  ")).isEqualTo("resume.pdf");
    }
}
