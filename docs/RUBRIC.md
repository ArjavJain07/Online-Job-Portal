# Marking rubric mapping

Where each rubric item is implemented in Career Hub, with file references so each
claim can be checked directly rather than taken on trust.

| # | Rubric item | Marks | Where to look |
|---|---|---|---|
| 1 | Problem Understanding & Solution Design | 8 | [`docs/PROJECT_PLAN.md`](PROJECT_PLAN.md) |
| 2 | Core Java Concepts | 10 | `domain/`, `domain/enums/`, `exception/`, `service/` |
| 3 | Database Integration (JDBC) | 8 | `repository/jdbc/`, `db/migration/`, datasource config |
| 4 | Servlets & Web Integration | 7 | `web/`, `security/`, `config/WebMvcConfig.java` |

---

## 1. Problem Understanding & Solution Design (8)

`docs/PROJECT_PLAN.md` is 4,259 lines across 16 numbered sections, written before
and alongside the code rather than after it.

- **Requirements are numbered and traceable.** 26 distinct specification IDs
  (`A-D1`, `E-F1`, `S-D5`, …) are defined, then cited in the code that implements
  them. Section 8 is a traceability matrix tying every requirement to its route,
  service and test.
- **Decisions record their alternatives.** Section 1.6 lists numbered design
  decisions (`D-1` … `D-28`) with the option taken *and the option rejected*. For
  example, D-21 records that optimistic locking was deliberately deferred, rather
  than leaving a silent gap.
- **Scope is bounded explicitly.** Section 16 lists what is deliberately *not*
  built and why — the honest counterpart to a feature list.
- **Trade-offs are stated, not hidden.** Section 4.2 documents an accepted
  account-enumeration trade-off in the login flow, including why the stricter
  alternative was not taken; §4.10 later revisits it when lockout was added and
  takes the opposite decision, with reasoning.

Three roles (administrator, employer, job seeker) were modelled from the problem
statement, each with its own dashboard, permissions and workflow.

---

## 2. Core Java Concepts (10)

| Concept | Example |
|---|---|
| **Enums with behaviour** | 11 enums in `domain/enums/`. `ApplicationStatus` encodes a state machine — only 14 of 49 possible transitions are legal, expressed as `allowedNext()` and verified by a parameterised test over all 49 pairs. |
| **Interfaces & polymorphism** | `MailService` with two implementations — `NoOpMailService` (default) and `SmtpMailService` — selected at runtime by configuration, so the app works with no mail server. |
| **Inheritance** | `FileValidationException extends BusinessRuleException extends RuntimeException` — a deliberate hierarchy so one handler can catch a family. |
| **Custom exceptions** | `exception/BusinessRuleException`, `FileValidationException`, `ResourceNotFoundException`, translated to HTTP responses in `web/advice/GlobalExceptionHandler`. |
| **Generics** | `Specification<Job>`, `Page<User>`, `List<Skill>`, `Optional<User>` throughout the repository and service layers. |
| **Collections** | `List`, `Set`, `Map`, `LinkedHashSet` (insertion-ordered skill parsing), `ArrayList` for ordered entity relations. |
| **Streams & lambdas** | `AdminStatisticsService:226` maps and de-duplicates decision records; `:242` computes a mean with `mapToDouble(...).average()`. |
| **`Optional`** | `UserRepository.findByEmail` returns `Optional<User>`; callers handle absence explicitly rather than risking null. |
| **`java.time`** | `LocalDate`, `LocalDateTime`, `ZoneId`, `Duration`. A `Clock` is injected everywhere rather than calling `now()` directly, which is what makes time-dependent logic testable. |
| **Encapsulation** | Entities expose intent-revealing mutators (`Job.assignSkills`) instead of raw setters, so an invariant cannot be bypassed. |

---

## 3. Database Integration (JDBC) (8)

The project integrates with the database at **two levels**, and both are
deliberate.

### 3a. The JDBC foundation

Everything below runs on JDBC, whether or not a line of `java.sql` is written:

- **JDBC URLs and drivers.** `jdbc:h2:file:./data/jobportal` for development
  (`application.properties:6`), `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
  in production (`application-prod.properties:10`). Both drivers are declared in
  `build.gradle` (`com.h2database:h2`, `org.postgresql:postgresql`).
- **Connection pooling.** HikariCP manages the pool (`HikariPool-1` in the server
  startup log). The same pooled `DataSource` serves JPA, the JDBC reports below
  and Flyway — one pool, not three database paths.
- **Raw SQL migrations.** `src/main/resources/db/migration/` holds seven versioned
  SQL scripts executed by Flyway over a JDBC connection, including a data-moving
  backfill in `V4__skills_entity.sql`.

### 3b. Hand-written JDBC where the ORM is the wrong tool

`repository/AdminReportingJdbcRepository` uses `NamedParameterJdbcTemplate` over
the **same pooled `DataSource`** Hibernate uses — one connection pool, not a
second database path. Two reports, each with a concrete reason JPA was the wrong
tool:

| Query | Why not JPA |
|---|---|
| **`headlineCounts`** — twelve dashboard counts in one statement | The page previously made **nine separate `select count(*)` round-trips** plus three list-sizes. JPQL cannot express it at all: a JPQL query selects over a root entity, and twelve unrelated aggregates have no root. The gain is eight fewer round-trips. |
| **`topSkillsByLiveJobDemand`** — most in-demand skills | `GROUP BY` over four tables ordered by the aggregate, top 10. JPQL has no `LIMIT`, so Spring Data would fire a second count query for a `Page` that is thrown away; and the result is a label and a number, never an entity. |

Both are read-only — no migration was needed.

**Portability was designed in, then verified.** The SQL deliberately avoids every
construct that differs between H2 and PostgreSQL: no date or string functions
(`activeSince` is computed in Java and passed as a parameter), no `ORDER BY`
ordinal, no `FILTER (WHERE …)` or `DISTINCT ON`, and ties break on `skills.id`
rather than `label` — because ordering text invokes a collation, and byte order
puts `SQL` before `Spring Boot` while `en_US.UTF-8` does the opposite, so tied
rows would reorder between development and production.

Both statements were then executed against the **live PostgreSQL 14 database**,
not merely assumed portable. `headlineCounts` in particular has no `FROM` clause,
which the SQL standard does not require engines to accept; it returns one row of
twelve counts on PostgreSQL correctly.

`AdminReportingJdbcRepositoryTest` is **differential**: every case asserts the SQL
against the JPA queries it replaced, because hand-written SQL's real failure mode
is running cleanly and returning plausible but wrong numbers.
### 3c. Working at the JDBC level in practice

The strongest evidence of understanding this layer is a defect that was diagnosed
*at* it. Deploying to PostgreSQL, the application aborted during startup with:

```
ERROR: operator does not exist: text ~~ bytea
```

`UserRepository.search()` built a LIKE pattern as `concat('%', :q, '%')`. With a
null `:q`, PostgreSQL received an untyped parameter, resolved `||` to
`bytea || bytea`, and the comparison failed. H2 had inferred the type and hidden
it. The fix was to type the parameter explicitly with `cast(:q as String)`.

Two related portability findings are documented in `V4__skills_entity.sql`:
`REGEXP_REPLACE` replaces *every* match on H2 and rejects the `g` flag, while
PostgreSQL replaces only the *first* without it — a one-line query that would have
passed every test and silently corrupted production data. The migration uses a
character walk instead.

---

## 4. Servlets & Web Integration (7)

The application is a servlet application. Spring MVC's front controller
(`DispatcherServlet`) runs inside embedded Tomcat; the project works with the
Servlet API directly wherever that is the right level.

| Servlet concept | Where |
|---|---|
| **Front controller** | Spring MVC `DispatcherServlet` routing to `@Controller` classes in `web/admin`, `web/employer`, `web/seeker`, `web/common` |
| **`HttpServletRequest` / `HttpServletResponse`** | Used directly in 20 files, e.g. `security/LoginFailureHandler:39`, `web/support/SafeRedirects` |
| **`HttpSession`** | Login state, flash messages, and the interview unlock time deliberately carried in the session rather than a query string |
| **Servlet filters** | Spring Security is a filter chain (`config/SecurityConfig`); authentication, authorisation and CSRF are filters |
| **Interceptors** | `security/CurrentUserInterceptor implements HandlerInterceptor`, `preHandle` at line 29, registered in `config/WebMvcConfig` |
| **Session configuration** | `server.servlet.session.timeout=60m`, `http-only=true`, `same-site=lax` (`application.properties:55-57`) |
| **File upload** | Servlet 3.0 multipart — `spring.servlet.multipart.max-file-size=10MB` (`:49`), resumes validated by content, not extension |
| **Request forwarding & redirects** | Post/Redirect/Get throughout; `SafeRedirects` validates a `Referer` before honouring it |
| **Error handling** | `web/advice/GlobalExceptionHandler` maps exceptions to status codes and error pages; whitelabel disabled |
| **View rendering** | Thymeleaf templates in `src/main/resources/templates`, server-rendered HTML |

**On Spring MVC vs. plain servlets:** this project uses Spring MVC rather than
hand-written `HttpServlet` subclasses. Spring MVC *is* servlet technology — a
single `DispatcherServlet` dispatching to handler methods — and it is what the
same application would be written with professionally. The Servlet concepts the
rubric names (request/response, session, filters, forwarding, multipart) are all
present and are listed above with the file that uses them.

---

## Beyond the rubric

- **602 automated tests** (JUnit 5, MockMvc, Spring Security Test).
- **Versioned schema migrations** with Flyway, verified against a copy of the
  production database before each deployment.
- **Deployed and running** on an Ubuntu VPS: systemd service, nginx reverse proxy,
  PostgreSQL 14, firewalled, with database backups.
