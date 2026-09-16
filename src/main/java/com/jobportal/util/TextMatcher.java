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
    private static String normalise(String value) {
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
