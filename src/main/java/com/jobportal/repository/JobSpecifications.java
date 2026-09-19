package com.jobportal.repository;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.Skill;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.util.SkillParser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // Title, description or company name contains the text, OR one of the job's skills
    // matches it as a whole word (Section 7.9, 10.8).
    //
    // WHY THE SKILLS HALF IS NO LONGER A LIKE '%text%'
    // It used to be a fourth `like` over the jobs.skills CSV column, which is where
    // searching "Java" started returning every "JavaScript" job: `%java%` matches
    // "JavaScript" inside the stored text and there is no way to tell the two apart in a
    // CSV. Skills are now rows, so this asks the right question instead - "does this job
    // list a skill in which the typed text appears as a whole token" - using exactly the
    // rule TextMatcher.containsPhrase uses on free text. "java" therefore matches the
    // skill "Java" but not "JavaScript", while "spring" still matches "Spring Boot"
    // (word boundary, not string equality), which is what a search box has to do.
    //
    // Title, description and company name keep their `like '%text%'`. That is not an
    // oversight: those are prose, and a substring search over prose is what a visitor
    // typing half a word expects. Only the skills column was claiming a precision it
    // could not deliver.
    public static Specification<Job> keyword(String text) {
        String pattern = likePattern(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                skillPhraseMatches(root, query, cb, text),
                cb.like(cb.lower(root.get("description")), pattern, '\\'),
                cb.like(cb.lower(root.get("employer").get("companyName")), pattern, '\\'));
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
