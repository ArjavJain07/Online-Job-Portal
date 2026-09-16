package com.jobportal.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Normalises a comma-separated skills string typed by a user (Section 7.8). Used for
// both Job.skills and SeekerProfile.skills, and by the recommendation scorer.
public final class SkillParser {

    private static final int MAX_SKILLS = 30;

    private SkillParser() {
    }

    // The normalised value that gets stored: split on commas, each skill trimmed and
    // collapsed to single spaces, blanks dropped, case-insensitive duplicates removed
    // (the first spelling is kept), at most 30 skills, joined back with ", ".
    public static String parse(String csv) {
        return String.join(", ", parseList(csv));
    }

    // The lower-case set used for matching against another skill list (Section 7.8).
    public static Set<String> keys(String csv) {
        Set<String> keys = new LinkedHashSet<>();
        for (String skill : parseList(csv)) {
            keys.add(skill.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static List<String> parseList(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }

        Set<String> seenLowerCase = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String collapsed = raw.trim().replaceAll("\\s+", " ");
            if (collapsed.isEmpty()) {
                continue;
            }
            String lower = collapsed.toLowerCase(Locale.ROOT);
            if (seenLowerCase.contains(lower)) {
                continue;
            }
            seenLowerCase.add(lower);
            result.add(collapsed);
            if (result.size() == MAX_SKILLS) {
                break;
            }
        }
        return result;
    }
}
