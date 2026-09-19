package com.jobportal.util;

import java.util.Locale;

// Loose phrase matching used by the recommendation scorer (Section 7.8): case-insensitive
// and punctuation-tolerant, but whole-word only, so "java" never matches "JavaScript".
public final class TextMatcher {

    private TextMatcher() {
    }

    // True when phrase appears as a whole word (or whole punctuation-joined token, such
    // as "node.js" or "c++") inside text.
    public static boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null) {
            return false;
        }
        String normalisedPhrase = normalise(phrase);
        if (normalisedPhrase.isEmpty()) {
            return false;
        }
        String normalisedText = normalise(text);
        return (" " + normalisedText + " ").contains(" " + normalisedPhrase + " ");
    }

    // Lower-cases the value and replaces every character other than letters, digits, +
    // and # with a space, then collapses runs of spaces. This makes "Node.js", "node js"
    // and "NODE-JS" all compare equal, while keeping "c++" and "c#" intact.
    //
    // Public since the skills-as-an-entity change (Section 10.8): Skill.slug is defined as
    // exactly this function of the typed label, so "the canonical key of a skill" and "the
    // token containsPhrase() looks for in a title or description" are the same string by
    // construction, not by two definitions that happen to agree today. That is what lets
    // RecommendationScorer's rule 1 (skill identity) and rules 2-3 (phrase in text) stay
    // coherent: a seeker skill can never match a job's free text under rules 2-3 while
    // failing to match the identical skill under rule 1.
    public static String normalise(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '+' || c == '#') {
                result.append(c);
            } else {
                result.append(' ');
            }
        }
        return result.toString().replaceAll("\\s+", " ").trim();
    }
}
