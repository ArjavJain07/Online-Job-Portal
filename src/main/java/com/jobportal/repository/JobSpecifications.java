package com.jobportal.repository;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.Skill;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.util.SearchIndex;
import com.jobportal.util.SkillParser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

// Reusable filter building blocks for JobRepository (Section 7.9). JobSearchService and
// the admin/employer list controllers chain these with Specification.and(...), always
// starting from live(today) on public/seeker searches. Kept as static methods on a final
// class, not a repository, because a Specification is a query fragment, not a query.
public final class JobSpecifications {

    private JobSpecifications() {
    }

    // A job is Live when it is approved, its deadline has not passed and the employer
    // account is still enabled. Mirrors Job.isLive(today) (11.3 contract item 4).
    public static Specification<Job> live(LocalDate today) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), JobStatus.APPROVED),
                cb.greaterThanOrEqualTo(root.get("applicationDeadline"), today),
                cb.isTrue(root.get("employer").get("enabled")));
    }

    // ---- Keyword search and relevance (Section 7.9, 10.8, 10.9) ----

    // How much each place a typed phrase can be found is worth to a job's relevance score.
    // Public and named because "why did this job come first" has to be answerable from
    // outside this class: JobSearchService.matchReasons quotes the same four places back
    // to the visitor, and JobSearchRelevanceTest pins the ordering these numbers produce.
    //
    // The numbers are chosen so that ONE higher place always beats ANY combination of
    // lower ones (100 > 60 + 30 + 10), which is what makes the ranking explainable rather
    // than merely plausible: a job is ahead of another because of the single best place its
    // phrase was found, and the lower matches only break ties between jobs whose best place
    // is the same. A job titled "Java Developer" is therefore always above a job that
    // merely mentions Java in its description, whatever else the second job matches.
    public static final int TITLE_WEIGHT = 100;
    public static final int SKILL_WEIGHT = 60;
    public static final int COMPANY_WEIGHT = 30;
    public static final int DESCRIPTION_WEIGHT = 10;

    // The job's title, description or company name contains the typed phrase as whole
    // words, OR one of its skills does (Section 7.9, 10.8, 10.9).
    //
    // WHY THERE IS NO LONGER A LIKE '%text%' ANYWHERE IN HERE
    // The skills half stopped being a substring search in Section 10.8, when skills became
    // rows: `%java%` matches "JavaScript" inside stored text and no amount of care can tell
    // the two apart, so it now asks "does this job list a skill in which the typed text
    // appears as a whole token" against Skill.slug. That half is unchanged below, weights
    // and all - it was right.
    //
    // What 10.8 left behind, and 10.9 finishes, is the other three. They kept
    // `like '%text%'` on the argument that they are prose and a visitor typing half a word
    // expects a substring match. That argument does not survive contact with the site: the
    // seeded Frontend Developer job lists HTML, CSS, JavaScript and React and no Java at
    // all, and searching "java" returned it anyway, because its description says
    // "interfaces with HTML, CSS and JavaScript". The skills half excluded it correctly and
    // the prose LIKE dragged it back in, so the precision 10.8 bought was worth nothing to
    // anybody actually using the search box.
    //
    // The two behaviours cannot both be had. "java matches part of a word" and "java does
    // not match javascript" are the same rule read twice, so choosing precision costs the
    // prefix match: searching "develop" no longer finds "Developer". That is the accepted
    // trade (Section 10.9), and it buys one definition of matching across the entire
    // application instead of two that disagree - the skills branch, these three prose
    // columns, RecommendationScorer's rules 2 and 3, and the "why did this appear" line on
    // each result card now all mean the same thing by "matches".
    //
    // The prose columns are matched against the persisted index V7 added rather than
    // against the text itself: search_title holds SearchIndex.of(title), and the pattern is
    // SearchIndex.phrasePattern(text). See SearchIndex for why the padding is stored rather
    // than assembled in SQL, and why no LIKE escaping is needed on these three (it is still
    // needed, and still applied, by locationContains below, which really is a substring
    // search over raw text).
    //
    // A phrase that normalises to nothing - the visitor typed only punctuation, "%" or
    // "---" - matches NO job rather than every job. That is the same answer
    // TextMatcher.containsPhrase gives for an empty phrase and the same answer the skills
    // branch has always given, and it is the safe one: the alternative is a stray keystroke
    // silently turning a filtered search into an unfiltered one.
    public static Specification<Job> keyword(String text) {
        String pattern = SearchIndex.phrasePattern(text);
        return (root, query, cb) -> {
            if (pattern == null) {
                return cb.disjunction();
            }
            return cb.or(
                    cb.like(root.get("searchTitle"), pattern),
                    skillPhraseMatches(root, query, cb, text),
                    cb.like(root.get("employer").get("searchCompanyName"), pattern),
                    cb.like(root.get("searchDescription"), pattern));
        };
    }

    // Orders a keyword search by how well each job matches, best first (Section 10.9).
    //
    // WHY THIS IS A Specification AND NOT A Sort
    // Spring Data's Sort can only name mapped properties, and relevance is not one - it is
    // a function of the row AND of what was typed, so it cannot be stored. Handing
    // `findAll(spec, pageable)` a Pageable with Sort.unsorted() and setting the order from
    // inside a Specification is the supported way round that, not a trick: Spring Data's
    // own SimpleJpaRepository.getCountQuery clears the order list with the comment "Remove
    // all Orders the Specifications might have applied", which only makes sense if
    // Specifications are expected to apply them. The count query beside the page is
    // therefore unaffected, and so is JobSkillFacetsImpl, which sets its own order after
    // calling toPredicate - but this Specification is only ever chained onto the final
    // paged query anyway (JobSearchService.search), never onto the one the facet counts are
    // built from.
    //
    // WHY IT IS STABLE
    // Three keys, and the last one is unique: score descending, then approvedAt descending
    // (the existing "newest" order, so equally relevant jobs stay in the order the rest of
    // the site would have shown them), then id descending. No two rows can tie on all
    // three, so page 2 cannot repeat or drop a job that page 1 already showed - which is
    // the failure a two-key relevance sort produces on the day two jobs are approved in the
    // same second.
    //
    // A phrase that normalises to nothing leaves the score out of the order entirely rather
    // than ordering by a constant. That is not only tidier: `order by 0 desc` is a positional
    // reference on PostgreSQL ("ORDER BY position 0 is not in select list"), so emitting a
    // bare literal here would be a query that runs on H2 and fails in production - exactly
    // the shape of bug this whole section is about. keyword() returns no rows for such a
    // phrase, so there is nothing to rank anyway.
    public static Specification<Job> relevanceOrder(String text) {
        return (root, query, cb) -> {
            List<Order> order = new ArrayList<>();
            if (SearchIndex.phrasePattern(text) != null) {
                order.add(cb.desc(relevanceScore(root, query, cb, text)));
            }
            order.add(cb.desc(root.get("approvedAt")));
            order.add(cb.desc(root.get("id")));
            query.orderBy(order);
            return cb.conjunction();
        };
    }

    // The score itself: one CASE per place the phrase can be found, added together. Plain
    // SQL:2003 that H2 and PostgreSQL 14 spell and evaluate identically - which is the
    // whole reason the ranking is arithmetic over CASE expressions rather than ts_rank over
    // a tsvector (Section 10.9). Every value in it is either a column, a varchar parameter
    // built in Java, or an integer literal inside a CASE; nothing is concatenated, cast or
    // assembled by the database.
    //
    // The four predicates are rebuilt here rather than shared with keyword()'s: they are
    // cheap, and a Criteria node that appears in both the WHERE clause and the ORDER BY of
    // one query is a thing to be sure about rather than to assume.
    private static Expression<Integer> relevanceScore(Root<Job> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String text) {
        String pattern = SearchIndex.phrasePattern(text);
        Expression<Integer> title = weighted(cb, cb.like(root.get("searchTitle"), pattern), TITLE_WEIGHT);
        Expression<Integer> skills = weighted(cb, skillPhraseMatches(root, query, cb, text), SKILL_WEIGHT);
        Expression<Integer> company = weighted(cb,
                cb.like(root.get("employer").get("searchCompanyName"), pattern), COMPANY_WEIGHT);
        Expression<Integer> description = weighted(cb,
                cb.like(root.get("searchDescription"), pattern), DESCRIPTION_WEIGHT);
        return cb.sum(cb.sum(title, skills), cb.sum(company, description));
    }

    private static Expression<Integer> weighted(CriteriaBuilder cb, Predicate matches, int weight) {
        return cb.<Integer>selectCase().when(matches, weight).otherwise(0);
    }

    // "One of this job's skills contains the text as a whole token." Expressed as four
    // patterns over Skill.slug - the whole slug, the first token, the last token, a
    // middle token - rather than one `like` over a SQL-side concat of the slug with
    // surrounding spaces, because every pattern here is then built in Java and sent as a
    // plain parameter. Building LIKE patterns in the database is what broke start-up on
    // PostgreSQL once already (UserRepository.search had to gain cast(:q as String) after
    // `concat('%', :q, '%')` resolved as bytea||bytea), and the escaping helper below
    // exists for the same "never hand the database a pattern it has to assemble" reason.
    //
    // An empty canonical form - the visitor typed only punctuation, say "%" or "_" -
    // matches no skill at all, so the disjunction is returned false and the search falls
    // back to the three prose columns.
    private static Predicate skillPhraseMatches(Root<Job> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String text) {
        String token = escapeLikeMetacharacters(SkillParser.canonical(text));
        if (token.isEmpty()) {
            return cb.disjunction();
        }
        Subquery<Long> matching = query.subquery(Long.class);
        Root<Job> subRoot = matching.from(Job.class);
        Join<Job, Skill> skill = subRoot.join("skills");
        Expression<String> slug = skill.get("slug");
        matching.select(subRoot.get("id")).where(cb.or(
                cb.equal(slug, token),
                cb.like(slug, token + " %", '\\'),
                cb.like(slug, "% " + token, '\\'),
                cb.like(slug, "% " + token + " %", '\\')));
        return root.get("id").in(matching);
    }

    // The faceted skill filter (Section 10.8): jobs that list exactly this skill, matched
    // by canonical key so the URL carries a stable slug rather than a display spelling.
    // A subquery, not a join, so a job listing the skill still counts once - a join in a
    // paged query multiplies rows and quietly corrupts both the page and its total.
    public static Specification<Job> hasSkill(String slug) {
        return (root, query, cb) -> {
            Subquery<Long> matching = query.subquery(Long.class);
            Root<Job> subRoot = matching.from(Job.class);
            Join<Job, Skill> skill = subRoot.join("skills");
            matching.select(subRoot.get("id")).where(cb.equal(skill.get("slug"), slug));
            return root.get("id").in(matching);
        };
    }

    // Job location contains the text; typing "remote" also matches workMode = REMOTE
    // (Section 6.4 S-F1).
    public static Specification<Job> locationContains(String location) {
        String pattern = likePattern(location);
        boolean remoteQuery = "remote".equalsIgnoreCase(location.trim());
        return (root, query, cb) -> {
            Predicate matchesLocation = cb.like(cb.lower(root.get("location")), pattern, '\\');
            if (remoteQuery) {
                return cb.or(matchesLocation, cb.equal(root.get("workMode"), WorkMode.REMOTE));
            }
            return matchesLocation;
        };
    }

    public static Specification<Job> hasCategory(JobCategory category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Job> hasJobType(JobType jobType) {
        return (root, query, cb) -> cb.equal(root.get("jobType"), jobType);
    }

    public static Specification<Job> hasWorkMode(WorkMode workMode) {
        return (root, query, cb) -> cb.equal(root.get("workMode"), workMode);
    }

    // Match rule from 6.4 S-F1: salaryMax >= minSalary.
    public static Specification<Job> salaryAtLeast(Integer minSalary) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("salaryMax"), minSalary);
    }

    // Match rule from 6.4 S-F1: minExperienceYears <= value.
    public static Specification<Job> maxExperience(Integer years) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("minExperienceYears"), years);
    }

    // approvedAt on or after the given instant (postedWithin filter, "approved since" charts).
    public static Specification<Job> approvedSince(LocalDateTime threshold) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("approvedAt"), threshold);
    }

    // Matches any one of the given statuses. Employer/admin lists pass several statuses
    // at once (for example PENDING_APPROVAL, APPROVED, REJECTED); a single value works too.
    public static Specification<Job> hasStatus(JobStatus... statuses) {
        return (root, query, cb) -> root.get("status").in((Object[]) statuses);
    }

    // Admin jobs list search: title or the posting employer's company name (Section 7.9).
    public static Specification<Job> titleOrCompanyContains(String text) {
        String pattern = likePattern(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("employer").get("companyName")), pattern, '\\'));
    }

    public static Specification<Job> hasEmployer(Long employerId) {
        return (root, query, cb) -> cb.equal(root.get("employer").get("id"), employerId);
    }

    // Employer job history (E-D4): the "Live" tab (APPROVED + not yet past deadline).
    public static Specification<Job> deadlineOnOrAfter(LocalDate today) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("applicationDeadline"), today);
    }

    // Employer job history (E-D4): the "Expired" tab (APPROVED + deadline passed).
    public static Specification<Job> deadlineBefore(LocalDate today) {
        return (root, query, cb) -> cb.lessThan(root.get("applicationDeadline"), today);
    }

    // Recommendation candidates (7.8): Live jobs the seeker has not already applied to.
    public static Specification<Job> notAppliedBy(Long seekerId) {
        return (root, query, cb) -> {
            Subquery<Long> applied = query.subquery(Long.class);
            Root<JobApplication> app = applied.from(JobApplication.class);
            applied.select(app.get("job").get("id")).where(cb.equal(app.get("seeker").get("id"), seekerId));
            return cb.not(root.get("id").in(applied));
        };
    }

    // Lower-cases, escapes LIKE metacharacters (\, %, _) and wraps the text in %...% so
    // it can be passed straight to cb.like(..., pattern, '\\').
    //
    // Still the right tool for the two searches that are deliberately substring searches
    // over raw text - locationContains ("pun" should find "Pune") and the admin jobs list's
    // titleOrCompanyContains - and no longer used by keyword(), which since Section 10.9
    // matches whole words against the V7 index with SearchIndex.phrasePattern instead.
    private static String likePattern(String text) {
        return "%" + escapeLikeMetacharacters(text.toLowerCase(Locale.ROOT)) + "%";
    }

    // \, % and _ are LIKE metacharacters; escaping them is what makes a visitor searching
    // for a literal "%" match only the jobs that really contain one instead of every job
    // on the site. Split out of likePattern because skillPhraseMatches needs the escaping
    // without the surrounding wildcards. (A canonical slug can never contain any of the
    // three - SkillParser.canonical turns them into spaces - so escaping there is belt
    // and braces, kept so that the two callers cannot drift apart.)
    private static String escapeLikeMetacharacters(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
