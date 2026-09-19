package com.jobportal.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Normalises a comma-separated skills string typed by a user (Section 7.8). Used when
// resolving the free text an employer or seeker types into domain.Skill rows
// (SkillService, Section 10.8), and by the V4 backfill's Java twin.
//
// WHAT CHANGED WHEN SKILLS BECAME AN ENTITY (Section 10.8)
// This class used to be the last word on skill identity: two skills were "the same" when
// their lower-cased spellings were equal. That is now only half the rule. Identity lives
// in Skill.slug, which is TextMatcher.normalise(label) - lower-cased, with every
// character other than a letter, digit, + or # turned into a space and runs of spaces
// collapsed. So "Node.js", "node js" and "NODE-JS" are one skill, while "c++", "c#" and
// "c" stay three. canonical(...) below is the single definition of that key, and the
// duplicate rule here uses it, so a list typed as "Node.js, node js" now stores one skill
// rather than two spellings of one skill.
//
// Deliberately NOT done here: synonym folding ("JS" -> "JavaScript", "Postgres" ->
// "PostgreSQL"). That needs a curated dictionary, and a wrong entry silently rewrites
// what a candidate said about themselves. Punctuation and spacing variants are merged
// because the project already treats them as the same phrase (TextMatcher, case E7 of
// Section 7.8); meaning-level synonyms are left alone.
public final class SkillParser {

    // Form-entry cap only (Section 7.8, JobForm/SeekerProfileForm). Deliberately NOT
    // applied by the V4 backfill: dropping skill 31 from a profile that already has 31
    // would be data loss, and the stored value can only have got that long by bypassing
    // the forms.
    private static final int MAX_SKILLS = 30;

    private SkillParser() {
    }

    // The normalised value that gets stored: split on commas, each skill trimmed and
    // collapsed to single spaces, blanks dropped, duplicates removed by canonical key
    // (the first spelling is kept), at most 30 skills, joined back with ", ".
    public static String parse(String csv) {
        return String.join(", ", labels(csv));
    }

    // The canonical keys used for matching against another skill list (Section 7.8) -
    // the same values that end up in skills.slug.
    public static Set<String> keys(String csv) {
        Set<String> keys = new LinkedHashSet<>();
        for (String skill : labels(csv)) {
            keys.add(canonical(skill));
        }
        return keys;
    }

    // The identity of one skill: see the class comment. Returns "" for a label with no
    // letters, digits, + or # in it at all ("---", "!!!", "   "), which is how the
    // backfill and SkillService recognise a garbage entry and drop it.
    public static String canonical(String label) {
        return label == null ? "" : TextMatcher.normalise(label);
    }

    // The display spellings, in the order they were typed: trimmed, inner whitespace
    // collapsed, blanks and canonically-empty entries dropped, duplicates removed by
    // canonical key, capped at 30. Public because SkillService needs the labels and the
    // order, not just the joined string.
    public static List<String> labels(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String collapsed = raw.trim().replaceAll("\\s+", " ");
            if (collapsed.isEmpty()) {
                continue;
            }
            String key = canonical(collapsed);
            if (key.isEmpty() || seenKeys.contains(key)) {
                continue;
            }
            seenKeys.add(key);
            result.add(collapsed);
            if (result.size() == MAX_SKILLS) {
                break;
            }
        }
        return result;
    }
}
