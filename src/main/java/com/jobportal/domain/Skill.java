package com.jobportal.domain;

import com.jobportal.util.SkillParser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

// One skill, shared by every job and every seeker profile that lists it (Section 10.8).
//
// WHY THIS EXISTS
// Skills used to be a comma-separated varchar(400) on jobs and on seeker_profiles. That
// made three things impossible or wrong: keyword search had to use LIKE '%java%', which
// also matches "JavaScript"; RecommendationScorer could only compare spellings, so a
// seeker's "Node.js" did not match a job's "Node JS" even though Section 7.8's own case
// E7 says they are the same skill; and a faceted filter with counts ("Java (24)") cannot
// be written at all against a CSV column. One row per skill, joined to jobs and seeker
// profiles, fixes all three.
//
// TWO COLUMNS, NOT ONE
//   slug  - the identity. TextMatcher.normalise(label): lower-cased, every character
//           other than a letter, digit, + or # replaced by a space, runs of spaces
//           collapsed. Unique, and the only thing ever compared. "Node.js", "node js"
//           and "NODE-JS" therefore share one row; "c", "c++" and "c#" stay three.
//   label - the spelling shown to people. The first spelling that created the row wins
//           (for rows created by the V4 backfill, the spelling most of the existing
//           corpus used - see that migration). A later employer who types "JAVA" is
//           attached to the existing row and their job renders "Java".
//
// This is NOT an admin-managed vocabulary: SkillService creates a row for any slug it has
// not seen, so the free-text boxes on the job form and the seeker profile keep working
// exactly as they did (Section 6.3 E-F1, 6.4 S-F4).
@Entity
@Table(name = "skills")
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 400, matching the width of the jobs.skills / seeker_profiles.skills columns this
    // table replaces. It is far more than any real skill needs, but a single entry in
    // those columns could in principle be 400 characters long, and the alternative -
    // a narrower column - would force the V4 backfill to decide what to do with a value
    // that does not fit. Silently truncating a candidate's skill is the one outcome that
    // is not acceptable here, so the column is simply wide enough that it cannot happen.
    @Column(nullable = false, length = 400, unique = true)
    private String slug;

    @Column(nullable = false, length = 400)
    private String label;

    protected Skill() {
        // JPA
    }

    private Skill(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    // Builds an unsaved Skill from a typed spelling. Used by SkillService before it
    // persists a slug nobody has used before, and by the pure unit tests of
    // RecommendationScorer, which need Skill values but no database.
    public static Skill of(String label) {
        String trimmed = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        return new Skill(SkillParser.canonical(trimmed), trimmed);
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getLabel() {
        return label;
    }

    // Identity is the slug, never the id: RecommendationScorer compares a seeker's skills
    // with a job's, and its unit tests build both sides with Skill.of(...), so neither
    // side has an id. Two rows can never share a slug (unique constraint), so slug
    // equality and row equality mean the same thing for persisted instances too.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Skill skill)) {
            return false;
        }
        return Objects.equals(slug, skill.slug);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(slug);
    }

    @Override
    public String toString() {
        return label;
    }
}
