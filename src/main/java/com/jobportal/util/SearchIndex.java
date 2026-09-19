package com.jobportal.util;

// The one definition of "searchable text" and "the pattern that finds a phrase in it"
// (Section 10.9). Two methods, both trivial, and the whole relevance-ranked job search
// rests on the fact that they are the only two.
//
// WHY THIS EXISTS AS ITS OWN CLASS
// The search has to ask the same question in two completely different places:
//
//   * in SQL, over thousands of rows at once, to decide which jobs match and how well
//     (JobSpecifications.keyword / relevanceOrder);
//   * in Java, over the ten jobs on the page the visitor is looking at, to say WHY each
//     one appeared (JobSearchService.matchReasons - Section 10.9's "explainable" rule).
//
// Written twice, those two would agree on the day they were written and drift the first
// time either was touched, and the drift would be invisible: the page would show a
// perfectly plausible "matched: title" under a job whose title does not match. So neither
// is written twice. of(...) produces the text that gets STORED (jobs.search_title,
// jobs.search_description, users.search_company_name - V7), phrasePattern(...) produces
// the LIKE pattern that gets SENT, and TextMatcher.containsPhrase - the in-memory version
// the recommendation scorer has used since Section 7.8 - is now expressed in terms of the
// same two strings. One rule, three callers.
//
// WHY PADDING RATHER THAN FOUR PATTERNS
// JobSpecifications.skillPhraseMatches asks the same whole-token question of a skill slug
// with four LIKE patterns (the whole value, first token, last token, middle token). That
// is the right shape there, because skills.slug is shared with the facet filter and with
// RecommendationScorer and must stay exactly what it is. For a column this change is
// adding from scratch, storing the padding once at write time is better: one LIKE instead
// of four in both the WHERE clause and the relevance score (twelve predicates saved on
// every keyword search), and - the part that matters more - the stored form and the
// pattern together ARE TextMatcher.containsPhrase's implementation, transliterated. There
// is nothing to prove equivalent because there is nothing different.
//
// WHY THE PATTERN IS BUILT HERE AND NEVER BY THE DATABASE
// Section 10.7's hard-won rule: never hand the database a pattern it has to assemble.
// `concat('%', :q, '%')` is what broke start-up on PostgreSQL once already (it resolved as
// bytea||bytea and UserRepository.search needed cast(:q as String) to recover), and a
// SQL-side `' ' || search_title || ' '` would be the same mistake in a new place. The
// padding therefore lives in the stored value and the wildcards live in a plain Java
// string, so every query sends one ordinary varchar parameter.
public final class SearchIndex {

    // What V7 writes into a search column that has never been indexed, and what
    // SearchIndexService looks for. Deliberately NOT a value of(...) can return: of("")
    // is two spaces, so "empty" and "indexed as empty" are different strings and a job
    // whose title is pure punctuation is not re-indexed on every start-up for ever.
    public static final String UNINDEXED = "";

    private SearchIndex() {
    }

    // The stored form of a piece of searchable text: normalised, then wrapped in one
    // leading and one trailing space so that every token in it - including the first and
    // the last - is surrounded by spaces, and a single "% token %" therefore means
    // "contains this whole word" with no special case at either end.
    //
    // Never longer than its input plus 2: normalise() maps one character to one character
    // and then collapses runs of spaces, so it can only shrink. That is what makes V7's
    // column lengths (source + 2) exact rather than generous.
    public static String of(String text) {
        return " " + TextMatcher.normalise(text == null ? "" : text) + " ";
    }

    // The LIKE pattern that finds the typed phrase as whole words inside a value produced
    // by of(...), or NULL when the visitor typed nothing a search can look for - only
    // punctuation, say "%" or "---", which normalises away to nothing. Callers must treat
    // null as "this query matches no text at all" rather than as "match everything";
    // JobSpecifications.keyword does exactly that.
    //
    // The result never needs LIKE-escaping, and it is not an oversight that none is
    // applied: normalise() turns every character except letters, digits, + and # into a
    // space, and \, % and _ are none of those four. A pattern built from it provably
    // cannot contain a metacharacter, which is a stronger guarantee than escaping one.
    // (JobSpecifications.locationContains still searches raw text with a substring LIKE
    // and still escapes - see JobSpecificationsTest#locationEscapesPercentAndUnderscore.)
    //
    // JobSpecifications nonetheless passes cb.like(..., pattern, '\\') rather than the
    // two-argument form, and that is a portability decision, not a second opinion about
    // escaping. Hibernate renders the two-argument form as `like ? escape ''`: an EMPTY
    // escape clause, which H2 accepts and which PostgreSQL documents as "escaping is
    // disabled" - probably fine, and a construct this application has never once executed
    // against PostgreSQL. The three-argument form renders `like ? escape '\'`, which is
    // character-for-character what the skills branch has been sending to production since
    // Section 10.8. Given a free choice between a spelling that is argued to work and one
    // that is observed to, on the half of the pair that no test here can reach, take the
    // observed one.
    public static String phrasePattern(String query) {
        String phrase = TextMatcher.normalise(query == null ? "" : query);
        return phrase.isEmpty() ? null : "% " + phrase + " %";
    }
}
