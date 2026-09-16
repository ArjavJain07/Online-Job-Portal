package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Unit tests for TextMatcher.containsPhrase (Section 7.8): whole-word, case-insensitive,
// punctuation-tolerant matching.
class TextMatcherTest {

    @Test
    void javaDoesNotMatchJavaScript() {
        assertThat(TextMatcher.containsPhrase("We need JavaScript developers", "java")).isFalse();
    }

    @Test
    void nodeDotJsMatchesNodeJsWrittenWithASpace() {
        assertThat(TextMatcher.containsPhrase("Experience with Node.js required", "node js")).isTrue();
    }

    @Test
    void nodeDashJsAlsoMatches() {
        assertThat(TextMatcher.containsPhrase("Comfortable with NODE-JS", "node js")).isTrue();
    }

    @Test
    void cPlusPlusIsKeptIntact() {
        assertThat(TextMatcher.containsPhrase("C++ and C# both required", "c++")).isTrue();
        assertThat(TextMatcher.containsPhrase("C++ and C# both required", "c#")).isTrue();
    }

    @Test
    void singleLetterCDoesNotMatchClerk() {
        assertThat(TextMatcher.containsPhrase("Front desk clerk position", "c")).isFalse();
    }

    @Test
    void matchIsCaseInsensitive() {
        assertThat(TextMatcher.containsPhrase("Strong SPRING BOOT experience", "spring boot")).isTrue();
    }

    @Test
    void matchAtTheStartOrEndOfTheText() {
        assertThat(TextMatcher.containsPhrase("Java developer wanted", "java")).isTrue();
        assertThat(TextMatcher.containsPhrase("Looking for a developer: Java", "java")).isTrue();
    }

    @Test
    void nullOrBlankInputsNeverMatch() {
        assertThat(TextMatcher.containsPhrase(null, "java")).isFalse();
        assertThat(TextMatcher.containsPhrase("java", null)).isFalse();
        assertThat(TextMatcher.containsPhrase("java developer", "   ")).isFalse();
        assertThat(TextMatcher.containsPhrase("", "java")).isFalse();
    }
}
