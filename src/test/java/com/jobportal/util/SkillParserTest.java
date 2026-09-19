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

    // Since Section 10.8 the duplicate rule is canonical identity, not lower-cased
    // equality: punctuation and spacing variants of one skill are one skill, which is
    // what stops "Node.js" and "Node JS" becoming two rows in the skills table and two
    // chips on a job.
    @Test
    void punctuationAndSpacingVariantsAreOneSkill() {
        assertThat(SkillParser.parse("Node.js, node js, NODE-JS")).isEqualTo("Node.js");
        assertThat(SkillParser.canonical("Node.js")).isEqualTo("node js");
        assertThat(SkillParser.canonical("NODE-JS")).isEqualTo("node js");
    }

    // ...and the other direction: + and # are kept, so these stay three distinct skills.
    // Folding them would quietly claim a C developer knows C++.
    @Test
    void plusAndHashAreKeptSoRelatedSkillsStayDistinct() {
        assertThat(SkillParser.keys("C, C++, C#")).containsExactly("c", "c++", "c#");
    }

    // A canonically empty entry has no identity to match on, so it is not a skill.
    // (The V4 backfill deliberately does NOT apply this rule to values already stored -
    // see that migration's header - because dropping something a person typed is only
    // acceptable for input that is being entered right now and can be re-typed.)
    @Test
    void entriesWithNothingButPunctuationAreDropped() {
        assertThat(SkillParser.parse("Java, ---, SQL")).isEqualTo("Java, SQL");
        assertThat(SkillParser.canonical("---")).isEmpty();
    }

    @Test
    void keysIsEmptyForBlankInput() {
        assertThat(SkillParser.keys(null)).isEmpty();
        assertThat(SkillParser.keys("")).isEmpty();
    }
}
