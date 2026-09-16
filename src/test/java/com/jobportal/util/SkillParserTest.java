package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

// Unit tests for SkillParser (Section 7.8).
class SkillParserTest {

    @Test
    void trimsAndCollapsesInteriorSpaces() {
        assertThat(SkillParser.parse("  Java  ,   Spring   Boot  ")).isEqualTo("Java, Spring Boot");
    }

    @Test
    void blanksBetweenCommasAreDropped() {
        assertThat(SkillParser.parse("Java,,   ,SQL")).isEqualTo("Java, SQL");
    }

    @Test
    void nullOrBlankGivesEmptyString() {
        assertThat(SkillParser.parse(null)).isEmpty();
        assertThat(SkillParser.parse("")).isEmpty();
        assertThat(SkillParser.parse("   ")).isEmpty();
    }

    // Duplicates ignoring case are removed, and the first spelling encountered is kept.
    @Test
    void duplicatesIgnoringCaseKeepFirstSpelling() {
        assertThat(SkillParser.parse("java, JAVA, Java, jAvA")).isEqualTo("java");
    }

    @Test
    void duplicatesAcrossDifferentSkillsAreEachKeptOnce() {
        assertThat(SkillParser.parse("Java, SQL, java, Git, sql")).isEqualTo("Java, SQL, Git");
    }

    @Test
    void atMost30SkillsAreKept() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 35; i++) {
            if (i > 1) {
                csv.append(",");
            }
            csv.append("Skill").append(i);
        }

        Set<String> keys = SkillParser.keys(csv.toString());

        assertThat(keys).hasSize(30);
        assertThat(keys).contains("skill1", "skill30");
        assertThat(keys).doesNotContain("skill31", "skill35");
    }

    @Test
    void keysAreLowerCased() {
        Set<String> keys = SkillParser.keys("Java, Spring Boot, SQL");
        assertThat(keys).containsExactlyInAnyOrder("java", "spring boot", "sql");
    }

    @Test
    void keysIsEmptyForBlankInput() {
        assertThat(SkillParser.keys(null)).isEmpty();
        assertThat(SkillParser.keys("")).isEmpty();
    }
}
