package com.jobportal.repository;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
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

    // Title, skills, description or company name contains the text (case-insensitive).
    public static Specification<Job> keyword(String text) {
        String pattern = likePattern(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("skills")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'),
                cb.like(cb.lower(root.get("employer").get("companyName")), pattern, '\\'));
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
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
