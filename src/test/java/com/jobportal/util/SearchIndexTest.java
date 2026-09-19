package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// The two halves of the search rule (Section 10.9), and the one property that makes the
// whole feature work: a value from of(...) matched against a pattern from
// phrasePattern(...) gives the same answer as TextMatcher.containsPhrase, which is the
// answer the database gives, which is the answer the results page prints.
class SearchIndexTest {

    @Test
    void ofNormalisesAndPadsWithOneSpaceAtEachEnd() {
        assertThat(SearchIndex.of("Java Developer")).isEqualTo(" java developer ");
        assertThat(SearchIndex.of("Node.js / React")).isEqualTo(" node js react ");
        assertThat(SearchIndex.of("  C++  and  C#  ")).isEqualTo(" c++ and c# ");
    }

    // of() must never be longer than its input plus the two padding spaces, because that is
    // what makes V7's column widths (source + 2) exact rather than a guess. normalise()
    // maps one character to one and then collapses runs, so it can only shrink.
    @Test
    void ofIsNeverLongerThanItsInputPlusTwo() {
        for (String text : new String[] {"", "x", "Java", "Node.js, React & Redux", "a   b", "!!!", "ünïcodé"}) {
            assertThat(SearchIndex.of(text).length())
                    .as("index length for \"%s\"", text)
                    .isLessThanOrEqualTo(text.length() + 2);
        }
    }

    // "" is the marker V7 writes and SearchIndexService looks for, so of() must never
    // produce it - otherwise a job whose title is pure punctuation would be re-indexed on
    // every start-up for ever.
    @Test
    void ofNeverReturnsTheUnindexedMarker() {
        assertThat(SearchIndex.of("")).isNotEqualTo(SearchIndex.UNINDEXED).isEqualTo("  ");
        assertThat(SearchIndex.of("---")).isNotEqualTo(SearchIndex.UNINDEXED);
        assertThat(SearchIndex.of(null)).isNotEqualTo(SearchIndex.UNINDEXED);
    }

    @Test
    void phrasePatternWrapsTheNormalisedPhraseInWordBoundaries() {
        assertThat(SearchIndex.phrasePattern("Java")).isEqualTo("% java %");
        assertThat(SearchIndex.phrasePattern("  Spring   Boot ")).isEqualTo("% spring boot %");
    }

    // A phrase with nothing searchable in it returns null, which callers must read as
    // "matches nothing". Returning "%%" or "% %" instead would turn one stray keystroke
    // into an unfiltered search.
    @Test
    void phrasePatternIsNullWhenThereIsNoWordToLookFor() {
        assertThat(SearchIndex.phrasePattern("%")).isNull();
        assertThat(SearchIndex.phrasePattern("_")).isNull();
        assertThat(SearchIndex.phrasePattern("---")).isNull();
        assertThat(SearchIndex.phrasePattern("   ")).isNull();
        assertThat(SearchIndex.phrasePattern(null)).isNull();
    }

    // No LIKE escaping is applied to a phrase pattern, and this is why that is safe rather
    // than an oversight: normalise() turns every character except letters, digits, + and #
    // into a space, and none of \, % or _ is one of those four. The pattern provably cannot
    // carry a metacharacter into the query.
    @Test
    void phrasePatternCanNeverContainALikeMetacharacter() {
        String pattern = SearchIndex.phrasePattern("100% pure_java \\ backend");
        assertThat(pattern).isEqualTo("% 100 pure java backend %");
        assertThat(pattern.substring(1, pattern.length() - 1)).doesNotContain("%", "_", "\\");
    }

    // The property the SQL depends on: "does this index contain this pattern" is exactly
    // "does this text contain this phrase". A LIKE of the form "% x %" is a plain substring
    // test, since neither side can hold a wildcard, so Java can check the same thing with
    // String.contains and the two must always agree.
    @Test
    void matchingTheIndexAgreesWithContainsPhrase() {
        String[][] cases = {
            {"We use Java and Spring Boot", "java", "true"},
            {"Interfaces with HTML, CSS and JavaScript", "java", "false"},
            {"Interfaces with HTML, CSS and JavaScript", "javascript", "true"},
            {"Java Developer", "java developer", "true"},
            {"Senior Java Backend Developer", "java developer", "false"},
            {"We run Node.js in production", "node js", "true"},
            {"Deployment is automated", "deploy", "false"},
            {"Java", "java", "true"},
        };
        for (String[] testCase : cases) {
            String text = testCase[0];
            String phrase = testCase[1];
            boolean expected = Boolean.parseBoolean(testCase[2]);

            String pattern = SearchIndex.phrasePattern(phrase);
            boolean viaIndex = pattern != null
                    && SearchIndex.of(text).contains(pattern.substring(1, pattern.length() - 1));

            assertThat(viaIndex).as("index match: \"%s\" in \"%s\"", phrase, text).isEqualTo(expected);
            assertThat(TextMatcher.containsPhrase(text, phrase))
                    .as("containsPhrase must agree: \"%s\" in \"%s\"", phrase, text)
                    .isEqualTo(expected);
        }
    }
}
