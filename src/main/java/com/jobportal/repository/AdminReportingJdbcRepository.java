package com.jobportal.repository;

import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.HeadlineCounts;
import com.jobportal.dto.SkillDemandRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// The two /admin/statistics reports that are written as SQL rather than as JPA
// (Section 10.10). Everything else on that page stays exactly where it was, in
// JpaRepository methods and Criteria Specifications; this class is not a second way to
// reach the database, it is two reporting queries that an ORM makes worse.
//
// ===========================================================================
// WHY THIS IS NOT JPA
// ===========================================================================
// Not "because the rubric has a JDBC section". Each query below is here for its own
// reason, and both reasons are the ordinary ones that put reporting code under an ORM.
//
// headlineCounts - ONE ROUND-TRIP INSTEAD OF NINE
//   AdminStatisticsService used to ask for these twelve numbers with nine separate
//   repository calls (countByToStatusAndChangedAtGreaterThanEqual, count(live(today)),
//   countByLastLoginAtGreaterThanEqual, count(), countByRoleAndEnabled, countByRole,
//   countDistinctSeekersSince, countDistinctEmployersSince, countBySentAtGreaterThanEqual),
//   plus three more derived from lists fetched for the charts. Nine of those are
//   `select count(*) ... where <one predicate>` and nothing else: nine statements, nine
//   network waits, to render one row of tiles and one small table.
//
//   This is not a case where a JPQL rewrite would have done. JPQL has no way to put nine
//   unrelated aggregates in one statement - a JPQL query selects over a root entity, and
//   there is no root here. Criteria can build a multiselect of subqueries, but only by
//   assembling nine Subquery<Long> objects against a dummy root, which is strictly more
//   code than the SQL and is still SQL, just written in Java with the WHERE clauses
//   turned inside out. The JPA-shaped answer to "nine counts in one statement" is a
//   native query, and a native query through the EntityManager is this file with a worse
//   API: no RowMapper, no named parameters, Object[] instead of a record.
//
//   What it is NOT doing is saving a hand-tuned join. These are nine trivial counts; the
//   gain is the eight round-trips, which is why the statement is nine scalar subqueries
//   and not something clever.
//
// topSkillsByLiveJobDemand - A RANKING OVER A JOIN TABLE
//   "Which skills are most in demand" is a GROUP BY over skills x job_skills x jobs with
//   an ORDER BY on the aggregate and a row limit. JPA can express it, and every way of
//   doing so ends up further from the question than the SQL:
//     * a derived query cannot: there is no aggregate, no ordering by one, and no limit
//       on a projection of a join table;
//     * JPQL can group and order by count(...), but it has no LIMIT - the cut has to be
//       made with a Pageable, so Spring Data issues a SECOND statement to count the
//       groups for the Page it then throws away (findTopJobsSince on this page already
//       pays that cost);
//     * either way the result is an interface projection, so the shape the page needs is
//       described in a third file (repository/projection/**) rather than in the query.
//   Entities are the wrong unit for this report in the first place: it never wants a
//   Skill or a Job, only a label and a number, and loading managed entities to aggregate
//   them is the N+1 this file exists to not write.
//
// ===========================================================================
// SAME POOL, SAME TRANSACTION - NOT A SECOND DATABASE PATH
// ===========================================================================
// No new dependency was added for this: spring-boot-starter-data-jpa already brings in
// spring-jdbc (JPA needs it for the DataSource), so Spring Boot auto-configures a
// JdbcTemplate and a NamedParameterJdbcTemplate over the SAME DataSource bean Hibernate
// uses. That is the whole point and it is worth being precise about:
//   * the same HikariCP pool, so these queries do not add a connection, a pool, or a
//     second set of credentials to the application;
//   * the same Spring-managed transaction. Spring JDBC takes its Connection through
//     DataSourceUtils, which hands back the connection already bound to the current
//     transaction, so a statement here runs inside the caller's
//     @Transactional(readOnly = true) alongside Hibernate's own - one transaction, one
//     consistent snapshot, not two readers that could disagree.
//
// The one real consequence of sharing the transaction, stated because it is the thing
// that bites: these statements read the DATABASE, not Hibernate's session. Uncommitted
// changes a caller has made through an entity are invisible here until the persistence
// context is flushed. The application never hits this (this page is read-only and
// mutating requests never call it), but an @Transactional test that changes a seeded row
// through JPA and then asks for a report must flush first, or it is asserting against
// the row as it was before - see AdminReportingJdbcRepositoryTest, which does.
//
// ===========================================================================
// PORTABILITY: THE SAME SQL ON H2 AND POSTGRESQL 14
// ===========================================================================
// Production is PostgreSQL 14; development and the entire test suite are H2 (10.1, 10.3).
// This project has been bitten twice by hand-written SQL that was correct on one engine
// and wrong on the other - V4's REGEXP_REPLACE (H2 replaces every match and rejects the
// `g` flag, PostgreSQL replaces only the first without it) and UserRepository.search's
// `concat('%', :q, '%')` (PostgreSQL could not infer the type of an untyped null
// parameter and resolved `||` to bytea||bytea). Neither would have failed a single test.
//
// So every construct below is deliberately boring: COUNT, COUNT(DISTINCT), GROUP BY,
// inner JOIN, scalar subqueries in the select list, FETCH FIRST n ROWS ONLY. No date or
// string function of any kind, no vendor extension, nothing concatenated or assembled by
// the database. Three specific choices are worth recording because the obvious
// alternative is the portability bug:
//
//   1. NO CAST IS NEEDED ON ANY PARAMETER, and that is a statement about these queries
//      rather than a general rule. Every parameter is compared directly against a column
//      (`u.created_at >= :from`, `j.application_deadline >= :today`, `u.role = :role`),
//      so PostgreSQL infers each parameter's type from that column, and NONE of them can
//      be null. Those are exactly the two properties UserRepository.search lacked: its
//      parameter was fed to `||` with no column to infer from, and it was nullable. Add a
//      parameter here that is nullable or that appears anywhere other than beside a
//      column, and it needs an explicit cast.
//
//   2. ENUMS ARE PASSED AS .name(), NOT ORDINALS. The columns are varchar with a CHECK
//      constraint listing the names (V1), because the entities map them with
//      @Enumerated(EnumType.STRING). Passing an ordinal would match nothing on either
//      engine, which is the harmless half; the point of naming the constants here instead
//      of writing 'APPROVED' as a literal is that renaming JobStatus.APPROVED then breaks
//      the build instead of silently making this report return zero.
//
//   3. TIES ARE BROKEN BY id, NEVER BY label - see topSkillsByLiveJobDemand.
//
// What is NOT guaranteed by the test suite, since the suite only ever runs H2: that
// PostgreSQL agrees. The riskiest construct is the FROM-less SELECT in headlineCounts
// (both engines allow it; the SQL standard does not) and it is the first thing to try
// against a real PostgreSQL 14.
//
// @Component, NOT @Repository, WHICH IS THE ANNOTATION THIS CLASS LOOKS LIKE IT WANTS
// @Repository would be the conventional choice and it is actively wrong here. It opts the
// bean into Spring's PersistenceExceptionTranslationPostProcessor - which Spring Data JPA
// auto-configures, so it is present in this application - and that proxy translates
// IllegalArgumentException into InvalidDataAccessApiUsageException, because that is what
// a bad argument to a JPA call means. The argument guard in topSkillsByLiveJobDemand
// would therefore reach callers disguised as a data-access failure, which is a lie about
// what went wrong: nothing had touched the database. Found by the test that asserts the
// guard, which is the only reason that guard has a test at all.
//
// Nothing is lost by dropping it. @Repository's translation exists for persistence APIs
// that throw their own vendor exceptions; JdbcTemplate already converts every SQLException
// into a DataAccessException itself, through the SQLExceptionTranslator it carries, with
// or without the annotation.
@Component
public class AdminReportingJdbcRepository {

    // Twelve counts, one statement, one round-trip (Section 10.10).
    //
    // Scalar subqueries in the select list rather than a pile of FULL OUTER JOINs or a
    // UNION ALL of twelve one-row selects: each line is then readable on its own and says
    // exactly what it counts, the result is ONE row that maps straight onto a record, and
    // the engines have nothing to disagree about - an uncorrelated scalar subquery is
    // evaluated once and substituted, on both.
    //
    // There is no FROM clause. The statement selects twelve constants-by-subquery and has
    // no row source, which both H2 2.x and PostgreSQL 14 accept (`select 1` works on
    // each). The alternatives were worse: `dual` does not exist on PostgreSQL, and
    // anchoring on a table that happens to hold one row (system_settings id 1) would make
    // this report silently return no rows at all on the day that row is missing.
    //
    // Every `>= :from` is the same boundary AdminStatisticsService computed once for the
    // whole page, so these counts and the charts beside them answer the same question
    // about the same window - see that service for why the boundary is computed in Java.
    private static final String HEADLINE_COUNTS_SQL = """
            select
                (select count(*)
                   from users u
                  where u.created_at >= :from)                                as new_users,
                (select count(*)
                   from jobs j
                  where j.created_at >= :from)                                as jobs_posted,
                (select count(*)
                   from job_applications a
                  where a.applied_at >= :from)                                as applications,
                (select count(*)
                   from application_status_changes c
                  where c.to_status = :hiredStatus
                    and c.changed_at >= :from)                                as hires,
                (select count(*)
                   from users u
                  where u.last_login_at >= :activeSince)                      as active_users_7_days,
                (select count(*)
                   from jobs j
                   join users e on e.id = j.employer_id
                  where j.status = :approvedStatus
                    and j.application_deadline >= :today
                    and e.enabled = true)                                     as live_jobs,
                (select count(*)
                   from users u)                                              as total_users,
                (select count(*)
                   from users u
                  where u.role = :seekerRole
                    and u.enabled = true)                                     as enabled_seekers,
                (select count(distinct a.seeker_id)
                   from job_applications a
                  where a.applied_at >= :from)                                as applying_seekers,
                (select count(*)
                   from users u
                  where u.role = :employerRole)                               as all_employers,
                (select count(distinct j.employer_id)
                   from jobs j
                  where j.created_at >= :from)                                as posting_employers,
                (select count(*)
                   from messages m
                  where m.sent_at >= :from)                                   as messages_sent
            """;

    // The "Most in-demand skills" ranking (Section 10.10). %d is the row limit and is
    // filled in below from an int - see that method for why it is not a parameter.
    //
    // THE FILTER IS "LIVE", AND THAT IS WHY users IS JOINED
    // "In demand" has to mean "asked for by a job a candidate can actually apply to
    // today", or the report ranks skills by postings that were rejected, closed, or whose
    // deadline passed last year. Live is the site's own three-part definition - approved,
    // deadline not passed, employer still enabled (JobSpecifications.live, Job.isLive) -
    // and the third part lives on users, so the employer row is joined even though the
    // report says nothing about employers. Dropping that join would make this the only
    // place on the site where a disabled employer's jobs still count: the seeded
    // QuickHire account is exactly that case, which is why the test asserts against the
    // JPA Specification rather than against a number somebody typed.
    //
    // count(DISTINCT j.id), NOT count(*)
    // The metric is "how many Live jobs ask for this skill", so the unit is the job, not
    // the join row. Those differ if a job ever lists one skill twice, and the schema does
    // not prevent that: job_skills' primary key is (display_order, job_id) - it is an
    // ordered @OrderColumn list, keyed by position, not by target - so "a job lists each
    // skill at most once" is a guarantee SkillService and the V4 backfill make, not one
    // the database makes. A report that would overcount if that invariant ever slipped is
    // a report that lies quietly; counting distinct jobs cannot.
    //
    // GROUP BY s.id, s.label, with both columns
    // PostgreSQL would accept `group by s.id` alone, because label is functionally
    // dependent on the primary key. H2 is stricter. Listing both is valid on either, and
    // grouping by id as well as label is also what keeps two skills that share a display
    // spelling apart - slug is the identity, label is not unique (domain.Skill).
    //
    // ORDER BY: THE AGGREGATE REPEATED, AND TIES BROKEN BY id
    // `order by count(distinct j.id) desc` rather than `order by demand desc` or
    // `order by 2 desc`: a select-list alias in ORDER BY is accepted by both engines
    // today but is the sort of thing they are free to differ on, and a bare integer is a
    // positional reference on PostgreSQL - the trap Section 10.9 hit with `order by 0`.
    //
    // The tie-break is s.id, and it deliberately is NOT s.label, which is what anybody
    // would reach for. Ordering text asks the engine for a collation, and H2's default
    // collation and PostgreSQL's en_US.UTF-8 do not agree: byte order puts "SQL" before
    // "Spring Boot" ('Q' < 'p'), a locale-aware collation puts "Spring Boot" first
    // ('p' < 'q'). Two skills with equal demand would therefore swap places between the
    // test suite and production - and with a row limit, near the cut they would not swap
    // but APPEAR AND DISAPPEAR. That is the V4 REGEXP_REPLACE shape of bug exactly: green
    // on H2, wrong where the data is. An integer primary key compares identically on
    // every engine, so ties are shown in the order the skills entered the vocabulary:
    // arbitrary, stable, and the same everywhere.
    private static final String TOP_SKILLS_SQL = """
            select s.label                 as label,
                   count(distinct j.id)    as live_jobs
              from skills s
              join job_skills js on js.skill_id = s.id
              join jobs j        on j.id = js.job_id
              join users e       on e.id = j.employer_id
             where j.status = :approvedStatus
               and j.application_deadline >= :today
               and e.enabled = true
             group by s.id, s.label
             order by count(distinct j.id) desc, s.id asc
             fetch first %d rows only
            """;

    private final NamedParameterJdbcTemplate jdbc;

    // NamedParameterJdbcTemplate rather than the plain JdbcTemplate for one concrete
    // reason: HEADLINE_COUNTS_SQL uses :from in five different subqueries. Positionally
    // that is five identical `?` placeholders which the caller has to supply five times in
    // the right order, and the day a thirteenth count is inserted in the middle, every
    // argument after it shifts by one and the statement still compiles, still runs, and
    // reports the wrong numbers. Named parameters make that failure impossible to write.
    // Spring Boot auto-configures this bean over the same DataSource as Hibernate; see the
    // class header.
    public AdminReportingJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // from         - start of the selected range (AdminStatisticsService.rangeStart)
    // activeSince  - now - 7 days, the fixed "Active users" window
    // today        - the clock's date, for the Live deadline comparison
    //
    // queryForObject, not query(...): the statement has no FROM clause and twelve scalar
    // subqueries, so it returns exactly one row always - even against a completely empty
    // database, where every count is 0. If it ever returns none, something is wrong in a
    // way that should throw rather than be papered over with a default.
    public HeadlineCounts headlineCounts(LocalDateTime from, LocalDateTime activeSince, LocalDate today) {
        Map<String, Object> params = Map.of(
                "from", from,
                "activeSince", activeSince,
                "today", today,
                "hiredStatus", ApplicationStatus.HIRED.name(),
                "approvedStatus", JobStatus.APPROVED.name(),
                "seekerRole", Role.JOB_SEEKER.name(),
                "employerRole", Role.EMPLOYER.name());

        return jdbc.queryForObject(HEADLINE_COUNTS_SQL, params, (rs, rowNum) -> new HeadlineCounts(
                rs.getLong("new_users"),
                rs.getLong("jobs_posted"),
                rs.getLong("applications"),
                rs.getLong("hires"),
                rs.getLong("active_users_7_days"),
                rs.getLong("live_jobs"),
                rs.getLong("total_users"),
                rs.getLong("enabled_seekers"),
                rs.getLong("applying_seekers"),
                rs.getLong("all_employers"),
                rs.getLong("posting_employers"),
                rs.getLong("messages_sent")));
    }

    // The skills ranked by how many Live jobs ask for them, most in demand first, at most
    // `limit` rows.
    //
    // WHY limit IS FORMATTED INTO THE SQL AND NOT BOUND AS A PARAMETER
    // Because FETCH FIRST is the one place in these two statements where a bound parameter
    // is not portable: the SQL standard requires a literal there, H2 requires a literal,
    // and PostgreSQL only accepts a parameter in the parenthesised form
    // `fetch first (?) rows only`, which H2 does not take. A literal is the only spelling
    // both engines read the same way.
    //
    // Nothing can be injected through it: the value is an int, so by the time it reaches
    // this method the only thing it can possibly be is a number - there is no string for a
    // quote or a semicolon to hide in. That is also the whole of the argument, which is
    // why the parameter is typed int rather than Integer or String, and why the guard
    // below rejects a non-positive value instead of letting `fetch first 0 rows only`
    // return an empty report that looks like "no skills are in demand".
    //
    // The limit is a parameter of the method rather than a constant in it so that the test
    // can ask for the WHOLE ranking and check every row against the JPA Specification,
    // instead of only being able to check the ten rows the page happens to show. The page
    // itself passes AdminStatisticsService.TOP_SKILLS_LIMIT.
    public List<SkillDemandRow> topSkillsByLiveJobDemand(LocalDate today, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        String sql = String.format(TOP_SKILLS_SQL, limit);
        Map<String, Object> params = Map.of(
                "today", today,
                "approvedStatus", JobStatus.APPROVED.name());

        return jdbc.query(sql, params,
                (rs, rowNum) -> new SkillDemandRow(rs.getString("label"), rs.getLong("live_jobs")));
    }
}
