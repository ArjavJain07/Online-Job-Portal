package com.jobportal.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.ActivityLog;
import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.Interview;
import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.JobView;
import com.jobportal.domain.Message;
import com.jobportal.domain.PasswordResetToken;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;

// Guards the Flyway baseline against the entity classes (Section 10.7).
//
// WHAT THIS TEST IS FOR, GIVEN THAT EVERY OTHER TEST ALREADY RUNS ON FLYWAY
// The test profile sets ddl-auto=validate, so all of the other integration tests already
// prove that db/migration produces a schema Hibernate will accept. But Hibernate's
// validator is narrower than people assume: it checks that every mapped table and column
// exists and that the JDBC type codes are compatible, and it checks nothing else. Column
// length, nullability, identity, primary keys, unique constraints and foreign keys are
// all invisible to it. A migration that declared `full_name varchar(20)` instead of
// varchar(100), or forgot a foreign key, or made a not-null column nullable, would pass
// every one of the other tests and then truncate or corrupt data in production.
//
// So this test compares two schemas built from scratch, side by side:
//   1. the schema db/migration produces, via Flyway;
//   2. the schema Hibernate itself would generate from the entity classes.
// and asserts they agree on all of the above. Together with ddl-auto=validate elsewhere,
// that is the "verify, do not assume" half of the baseline: V1 was generated from
// Hibernate's own output rather than hand-written, and this keeps it that way.
//
// WHY THE REFERENCE USES THE POSTGRESQL DIALECT ON AN H2 DATABASE
// This is deliberate, not a mistake. Hibernate's DDL for the two databases differs in
// exactly one way - H2 gets native `enum ('A','B')` columns where PostgreSQL gets
// `varchar(n)` plus a check constraint - and V1 uses the PostgreSQL form for both, since
// it is ordinary SQL that H2 accepts too (V1's header explains the choice). Generating
// the reference with PostgreSQLDialect and then running it on H2 is therefore the precise
// invariant we want: "V1 must be what Hibernate would have generated for production."
// Using H2Dialect here would compare V1 against enum columns it deliberately does not
// use, and would fail for a reason that is not a bug.
//
// The reference is built with raw Hibernate rather than through a Spring context because
// a context would need a real PostgreSQL connection. The two naming strategies below are
// the ones Spring Boot's HibernateProperties applies by default; if they were ever wrong
// the reference schema would come out with columns like "fullName" and this test would
// fail loudly rather than quietly comparing the wrong thing.
class BaselineSchemaTest {

    private static final Class<?>[] ENTITIES = {
        ActivityLog.class, ApplicationStatusChange.class, Interview.class, Job.class, JobApplication.class,
        JobStatusChange.class, JobView.class, Message.class, PasswordResetToken.class, SeekerProfile.class,
        SystemSettings.class, User.class
    };

    // Flyway's own bookkeeping table exists only in the Flyway-built database, so it is
    // never part of the comparison. Matched case-insensitively: Flyway creates it with a
    // quoted lower-case name, while H2 upper-cases every unquoted identifier, so the two
    // spellings sit side by side in INFORMATION_SCHEMA depending on who made the table.
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Test
    void migrationsProduceTheSchemaHibernateExpects() throws Exception {
        String flywayUrl = memoryDatabase("baseline_flyway");
        String referenceUrl = memoryDatabase("baseline_reference");

        buildWithFlyway(flywayUrl);
        buildWithHibernate(referenceUrl);

        try (Connection flyway = connect(flywayUrl); Connection reference = connect(referenceUrl)) {
            assertThat(tables(flyway))
                    .as("tables created by db/migration")
                    .isEqualTo(tables(reference));

            // Compared one table at a time: when this does fail, the failure names the
            // table and shows only its columns, instead of a hundred-line diff of the
            // whole schema that nobody can read.
            for (String table : tables(reference)) {
                assertThat(columns(flyway, table))
                        .as("columns of %s (name -> type, length, nullable, identity)", table)
                        .isEqualTo(columns(reference, table));

                assertThat(keyColumns(flyway, table))
                        .as("primary key and unique constraints of %s", table)
                        .isEqualTo(keyColumns(reference, table));

                assertThat(foreignKeys(flyway, table))
                        .as("foreign keys of %s (column -> referenced table)", table)
                        .isEqualTo(foreignKeys(reference, table));
            }
        }
    }

    // A separate, blunter assertion about the thing V1's comments promise and the rest of
    // the suite cannot see: the two lockout columns exist and are still nullable. They are
    // nullable because ddl-auto=update could not have added them to the populated live
    // databases any other way (Section 4.10), and those databases are adopted by
    // baseline-on-migrate rather than rebuilt - so if a later migration ever tightened
    // them here, V1 would stop describing what production actually has.
    @Test
    void lockoutColumnsStayNullable() throws Exception {
        String url = memoryDatabase("baseline_lockout");
        buildWithFlyway(url);
        try (Connection c = connect(url)) {
            Map<String, String> users = columns(c, "USERS");
            assertThat(users).containsKeys("FAILED_LOGIN_ATTEMPTS", "LOCKOUT_UNTIL");
            assertThat(users.get("FAILED_LOGIN_ATTEMPTS")).contains("nullable=YES");
            assertThat(users.get("LOCKOUT_UNTIL")).contains("nullable=YES");
        }
    }

    // Every database name is unique per run, because the whole test suite shares one JVM
    // and one H2 in-memory namespace; a fixed name would let one test see another's
    // schema. DB_CLOSE_DELAY=-1 keeps the database alive between connections: without it
    // H2 discards an in-memory database the moment the last connection closes, so the
    // schema Flyway had just created would vanish when Flyway closed its own connection
    // and the comparison below would silently run against two empty databases.
    private String memoryDatabase(String prefix) {
        return "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, "sa", "");
    }

    private void buildWithFlyway(String url) {
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void buildWithHibernate(String url) throws Exception {
        Path script = Path.of("build", "schema", "baseline-reference.sql");
        Files.createDirectories(script.getParent());
        Files.deleteIfExists(script);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                // Without this Hibernate would ask the H2 connection what database it is
                // talking to and quietly switch back to H2Dialect, defeating the point.
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName())
                .applySetting("hibernate.physical_naming_strategy",
                        CamelCaseToUnderscoresNamingStrategy.class.getName())
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", script.toString())
                .build();

        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entity : ENTITIES) {
            sources.addAnnotatedClass(entity);
        }
        Metadata metadata = sources.buildMetadata();
        try (SessionFactory ignored = metadata.buildSessionFactory()) {
            // Building the factory is what writes the script; nothing is executed against
            // the database, because database.action is none.
        }

        // The generated script is one statement per line, `;`-terminated.
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            for (String line : Files.readAllLines(script)) {
                String sql = line.trim();
                if (sql.isEmpty() || sql.startsWith("--")) {
                    continue;
                }
                s.execute(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql);
            }
        }
    }

    private List<String> tables(Connection c) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select table_name from information_schema.tables "
                        + "where table_schema = 'PUBLIC' and table_type = 'BASE TABLE' order by table_name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!FLYWAY_HISTORY.equalsIgnoreCase(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    // Column name -> a single readable string, so an AssertJ map diff prints something a
    // person can act on rather than a wall of nested objects.
    private Map<String, String> columns(Connection c, String table) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select column_name, data_type, character_maximum_length, "
                        + "numeric_precision, is_nullable, is_identity from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and table_name = '" + table + "' order by column_name")) {
            while (rs.next()) {
                result.put(rs.getString(1), "type=" + rs.getString(2)
                        + " length=" + rs.getString(3)
                        + " precision=" + rs.getString(4)
                        + " nullable=" + rs.getString(5)
                        + " identity=" + rs.getString(6));
            }
        }
        return result;
    }

    // Constraint TYPE plus the columns it covers, never the constraint name: H2 invents
    // names like CONSTRAINT_7 for anything unnamed, and which table gets which number
    // depends only on the order the tables happened to be created in. Comparing those
    // would make the test fail whenever V1's statements were reordered, which is exactly
    // the kind of false alarm that gets a test deleted.
    private List<String> keyColumns(Connection c, String table) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select tc.constraint_type, listagg(kcu.column_name, ',') "
                        + "within group (order by kcu.ordinal_position) "
                        + "from information_schema.table_constraints tc "
                        + "join information_schema.key_column_usage kcu "
                        + "  on kcu.constraint_name = tc.constraint_name "
                        + " and kcu.table_schema = tc.table_schema "
                        + "where tc.table_schema = 'PUBLIC' and tc.table_name = '" + table + "' "
                        + "  and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE') "
                        + "group by tc.constraint_type, tc.constraint_name "
                        + "order by tc.constraint_type, 2")) {
            while (rs.next()) {
                result.add(rs.getString(1) + " (" + rs.getString(2) + ")");
            }
        }
        return result;
    }

    // Foreign keys as "column -> referenced table". Names are left out for the same reason
    // as above; V1 does pin the generated names down so the adopted live databases match,
    // but that is a property of the SQL text, not of the resulting schema.
    private List<String> foreignKeys(Connection c, String table) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select kcu.column_name, ccu.table_name "
                        + "from information_schema.table_constraints tc "
                        + "join information_schema.key_column_usage kcu "
                        + "  on kcu.constraint_name = tc.constraint_name "
                        + " and kcu.table_schema = tc.table_schema "
                        + "join information_schema.constraint_column_usage ccu "
                        + "  on ccu.constraint_name = tc.constraint_name "
                        + " and ccu.table_schema = tc.table_schema "
                        + "where tc.table_schema = 'PUBLIC' and tc.table_name = '" + table + "' "
                        + "  and tc.constraint_type = 'FOREIGN KEY' "
                        + "order by kcu.column_name, ccu.table_name")) {
            while (rs.next()) {
                result.add(rs.getString(1) + " -> " + rs.getString(2));
            }
        }
        return result;
    }
}
