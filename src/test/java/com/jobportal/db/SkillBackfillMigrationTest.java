package com.jobportal.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Proves that V4 MOVES THE DATA, not just that it creates the tables (Section 10.8).
//
// WHY THIS NEEDS ITS OWN TEST AT ALL
// Every other test in the suite runs against a database Flyway built from empty, where the
// old jobs.skills and seeker_profiles.skills columns have nothing in them at the moment V4
// runs - DataSeeder fills them afterwards, through the entity. So the entire backfill half
// of V4 is dead code as far as the rest of the suite is concerned: delete it and 430-odd
// tests still pass, and the first sign of trouble is a production database whose skills
// silently vanished. BaselineSchemaTest has the same blind spot from the other direction -
// it compares schemas and never looks at a row.
//
// So this test does the one thing that reproduces the real upgrade: migrate only as far as
// the schema that existed BEFORE V4, insert rows the way the old application would have,
// then let the rest of the migrations run and check what came out.
//
// Targeting version 1 rather than "whatever is just before 4" is deliberate. V2 and V3 add
// tables of their own and neither touches skills, so stopping at V1 gives the same
// pre-backfill state with no dependence on which migrations happen to sit between - and
// this file cannot see V3, which belongs to another change in flight.
class SkillBackfillMigrationTest {

    // The employer every fixture job belongs to; jobs.employer_id is a foreign key, so a
    // user row has to exist before any job does.
    private static final long EMPLOYER_ID = 1;

    @Test
    void backfillMovesEveryStoredSkillIntoTheNewTables() throws Exception {
        String url = memoryDatabase();
        migrateTo(url, "1");

        try (Connection c = connect(url)) {
            insertEmployer(c);
            // The spellings here are the cases the normalisation rules in V4's header
            // promise to handle, each one chosen for a different rule:
            //   J1  the ordinary case, plus odd spacing around the separators;
            //   J2  "node js" - the same skill as J3's "Node.js" under a different
            //       punctuation, which must collapse to ONE skills row;
            //   J3  a duplicate within one list ("Node.js" twice, differently cased) and
            //       "C++", which must NOT be folded into "C";
            //   J4  blank entries between commas, which are dropped;
            //   J5  "JAVA" - a third spelling of a skill two other jobs already use, to
            //       pin down which label wins.
            insertJob(c, 1, "Java , Spring  Boot ,SQL");
            insertJob(c, 2, "node js, Java");
            insertJob(c, 3, "Node.js, NODE.JS, C++, C");
            insertJob(c, 4, "Java,,   ,Excel");
            insertJob(c, 5, "JAVA");
            insertSeekerProfile(c, 1, 101, "Java, Node.js");
            insertSeekerProfile(c, 2, 102, null);
            insertSeekerProfile(c, 3, 103, "   ");
        }

        migrateTo(url, null);

        try (Connection c = connect(url)) {
            // One row per canonical key. "node js", "Node.js" and "NODE.JS" are one skill;
            // "C++" and "C" are two; "JAVA"/"Java" is one.
            assertThat(slugs(c)).containsExactlyInAnyOrder("java", "spring boot", "sql", "node js", "c++", "c",
                    "excel");

            // The winning label is the spelling the most rows used, ties broken
            // alphabetically. "Java" is used by J1, J2, J4 and the seeker (4) against
            // "JAVA" once, so "Java" wins. "Node.js" is used by J3 (deduped to one) and
            // the seeker (2) against "node js" once.
            Map<String, String> labels = labelsBySlug(c);
            assertThat(labels).containsEntry("java", "Java");
            assertThat(labels).containsEntry("node js", "Node.js");
            assertThat(labels).containsEntry("spring boot", "Spring Boot");
            assertThat(labels).containsEntry("c++", "C++");

            // Order and content of each job's list, exactly as typed, with duplicates and
            // blanks removed. display_order is renumbered from 0 with no gaps, which is
            // what an @OrderColumn list requires to load back without a null in it.
            assertThat(jobSkills(c, 1)).containsExactly("Java", "Spring Boot", "SQL");
            assertThat(jobSkills(c, 2)).containsExactly("Node.js", "Java");
            assertThat(jobSkills(c, 3)).containsExactly("Node.js", "C++", "C");
            assertThat(jobSkills(c, 4)).containsExactly("Java", "Excel");
            assertThat(jobSkills(c, 5)).containsExactly("Java");
            assertThat(displayOrders(c, "job_skills", "job_id", 4)).containsExactly(0, 1);

            assertThat(profileSkills(c, 1)).containsExactly("Java", "Node.js");
            assertThat(profileSkills(c, 2)).isEmpty();
            assertThat(profileSkills(c, 3)).isEmpty();

            // The old columns are left exactly as they were: they are the rollback
            // artefact, and V4 rewriting text a person typed is not part of the deal.
            assertThat(storedCsv(c, "jobs", 5)).isEqualTo("JAVA");
        }
    }

    // A token with no letters, digits, + or # at all. V4 keeps it rather than deciding on
    // its own that something a person typed was meaningless - and the guard at the end of
    // the migration depends on that, since "non-blank in, nothing out" is what it treats
    // as a bug worth stopping the deploy for.
    @Test
    void punctuationOnlySkillsAreKeptRatherThanDropped() throws Exception {
        String url = memoryDatabase();
        migrateTo(url, "1");
        try (Connection c = connect(url)) {
            insertEmployer(c);
            insertJob(c, 1, "---, Java");
        }

        migrateTo(url, null);

        try (Connection c = connect(url)) {
            assertThat(jobSkills(c, 1)).containsExactly("---", "Java");
        }
    }

    // The guard itself. There is no way to make the real backfill lose a row - that is the
    // point of it - so the invariant is broken from the other side: a job inserted with a
    // skills value and no matching rows, checked by re-running the guard's own query.
    // If this ever stops throwing, the guard has stopped guarding.
    @Test
    void theGuardFailsWhenARowsSkillsDidNotMakeItAcross() throws Exception {
        String url = memoryDatabase();
        migrateTo(url, "1");
        try (Connection c = connect(url)) {
            insertEmployer(c);
            insertJob(c, 1, "Java");
        }
        migrateTo(url, null);

        try (Connection c = connect(url); Statement s = c.createStatement()) {
            // A job added after the migration, with the old column set and no join rows -
            // exactly the state a backfill that quietly skipped a row would leave behind.
            s.execute("insert into jobs (id, employer_id, title, description, requirements, skills, category, "
                    + "job_type, work_mode, location, salary_min, salary_max, min_experience_years, openings, "
                    + "application_deadline, status, view_count, created_at) values "
                    + "(99, 1, 'Missed', 'd', 'r', 'Java', 'OTHER', 'FULL_TIME', 'ONSITE', 'Pune', 1, 2, 0, 1, "
                    + "date '2030-01-01', 'APPROVED', 0, timestamp '2026-01-01 10:00:00')");

            s.execute("create table guard_rerun (checked integer not null)");
            assertThatThrownBy(() -> s.execute("insert into guard_rerun (checked) "
                    + "select case when count(*) = 0 then 1 else null end from jobs j "
                    + "where coalesce(trim(j.skills), '') <> '' "
                    + "and not exists (select 1 from job_skills js where js.job_id = j.id)"))
                    .hasMessageContaining("NULL not allowed");
        }
    }

    // ---- fixtures and helpers ----

    private String memoryDatabase() {
        return "jdbc:h2:mem:skill_backfill_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, "sa", "");
    }

    // target = null means "all the way", which is what the real deploy does.
    private void migrateTo(String url, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(url, "sa", "")
                        .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(org.flywaydb.core.api.MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private void insertEmployer(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("insert into users (id, full_name, email, password_hash, role, enabled, created_at) values "
                    + "(" + EMPLOYER_ID + ", 'Acme', 'hr@acme.test', 'x', 'EMPLOYER', true, "
                    + "timestamp '2026-01-01 10:00:00')");
        }
    }

    private void insertJob(Connection c, long id, String skills) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("insert into jobs (id, employer_id, title, description, requirements, skills, category, "
                    + "job_type, work_mode, location, salary_min, salary_max, min_experience_years, openings, "
                    + "application_deadline, status, view_count, created_at) values "
                    + "(" + id + ", " + EMPLOYER_ID + ", 'Job " + id + "', 'd', 'r', '" + skills + "', "
                    + "'SOFTWARE_DEVELOPMENT', 'FULL_TIME', 'ONSITE', 'Pune', 1, 2, 0, 1, date '2030-01-01', "
                    + "'APPROVED', 0, timestamp '2026-01-01 10:00:00')");
        }
    }

    private void insertSeekerProfile(Connection c, long id, long userId, String skills) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("insert into users (id, full_name, email, password_hash, role, enabled, created_at) values "
                    + "(" + userId + ", 'Seeker " + id + "', 'seeker" + id + "@test', 'x', 'JOB_SEEKER', true, "
                    + "timestamp '2026-01-01 10:00:00')");
            s.execute("insert into seeker_profiles (id, user_id, skills, experience_years) values "
                    + "(" + id + ", " + userId + ", " + (skills == null ? "null" : "'" + skills + "'") + ", 0)");
        }
    }

    private List<String> slugs(Connection c) throws Exception {
        return strings(c, "select slug from skills");
    }

    private Map<String, String> labelsBySlug(Connection c) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select slug, label from skills order by slug")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    private List<String> jobSkills(Connection c, long jobId) throws Exception {
        return strings(c, "select s.label from job_skills js join skills s on s.id = js.skill_id "
                + "where js.job_id = " + jobId + " order by js.display_order");
    }

    private List<String> profileSkills(Connection c, long profileId) throws Exception {
        return strings(c, "select s.label from seeker_profile_skills ps join skills s on s.id = ps.skill_id "
                + "where ps.seeker_profile_id = " + profileId + " order by ps.display_order");
    }

    private List<Integer> displayOrders(Connection c, String table, String ownerColumn, long ownerId)
            throws Exception {
        List<Integer> result = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select display_order from " + table + " where " + ownerColumn
                        + " = " + ownerId + " order by display_order")) {
            while (rs.next()) {
                result.add(rs.getInt(1));
            }
        }
        return result;
    }

    private String storedCsv(Connection c, String table, long id) throws Exception {
        return strings(c, "select skills from " + table + " where id = " + id).get(0);
    }

    private List<String> strings(Connection c, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
