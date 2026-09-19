# Hosting the portal on Render

The portal normally runs on your own machine with `run.bat`. This guide puts a
copy on the internet so you can share a link. Nothing here changes how the local
version works: the local run still uses the H2 file database with no setup.

Everything Render needs is already in the repository:

| File | What it does |
|---|---|
| `Dockerfile` | Builds the runnable jar, then packs it with a Java runtime |
| `render.yaml` | Describes the web service and the Postgres database |
| `src/main/resources/application-prod.properties` | Settings used only on the hosted copy |

---

## Steps

1. **Push the project to GitHub.** Already done: <https://github.com/ArjavJain07/Online-Job-Portal>
2. **Create a Render account** at <https://render.com> and sign in with GitHub.
3. In the dashboard choose **New → Blueprint**.
4. Pick the `Online-Job-Portal` repository. Render finds `render.yaml` by itself.
5. Give the group a name (for example `job-portal`) and click **Apply**.
6. Render now creates two things and starts the first build:
   - **online-job-portal** — the web service
   - **jobportal-db** — the Postgres database
7. The first build takes roughly 5 to 10 minutes, because it downloads the Java
   dependencies. Later builds are faster.
8. When the service turns **Live**, open the URL Render shows, which looks like
   `https://online-job-portal.onrender.com`.

## Logging in to the hosted copy

The demo data is seeded on the first start, exactly as it is locally, so the
employer and job seeker accounts from the README work there too.

The **admin password is different**. Render generates a random one so it isn't
published in this repository. To read it: open the web service → **Environment**
tab → reveal `ADMIN_PASSWORD`. The admin email stays `admin@jobportal.local`.

If you would rather the hosted login page did not list the demo accounts, set
`SHOW_DEMO_CREDENTIALS` to `false` in the same Environment tab.

---

## What the free plan means for this project

| Limit | Effect on the portal | What to do about it |
|---|---|---|
| The service sleeps after 15 minutes idle | The first visit afterwards takes about a minute to load | Open the link a minute before a demo so it is awake |
| No permanent disk | Uploaded resumes disappear when the service restarts | Expected. The app shows "the file is no longer available" instead of an error, and demo resumes reappear whenever the database is reseeded |
| Free database expires 30 days after creation | The site stops working once it is deleted | Create a fresh database before the exam, or upgrade the database plan |
| 512 MB of memory | Plenty for this app | `JAVA_OPTS` already caps the JVM heap to fit |

**For the actual presentation, still demo from your laptop.** It starts
instantly, uploads survive, and it works without internet. Treat the hosted link
as a convenience for sharing, not the main demo.

---

## Useful things to know

**Redeploying.** Every push to `main` triggers a new deploy, because
`autoDeployTrigger: commit` is set in `render.yaml`.

**Watching a deploy.** Open the service and use the **Logs** tab. A healthy start
now begins with a few Flyway lines and ends with `Started JobPortalApplication`,
followed by the seeding line
`Demo dataset loaded (Section 13): 10 users, 12 jobs, 15 applications`.

**The schema is managed by Flyway** (PROJECT_PLAN 10.7), not by Hibernate. There
is one thing to do **before the first deploy that includes it**, once only: check
that the hosted database already has the login-lockout columns, and add them if it
does not. Flyway adopts the existing database at version 1 rather than rebuilding
it, so it takes version 1 at its word.

```sql
-- expect 2
select count(*) from information_schema.columns
 where table_name = 'users'
   and column_name in ('failed_login_attempts', 'lockout_until');

-- only if that returned 0
alter table users add column failed_login_attempts integer;
alter table users add column lockout_until timestamp(6);
```

Nothing is at risk if this is forgotten: the app refuses to start and names the
missing column, the health check fails, and Render keeps the previous version
running. Fix it and redeploy.

**Starting the data again.** Delete the database in Render and apply the blueprint
again. The app recreates the schema and reseeds on the next start.

If you instead connect with `psql` and drop the tables by hand, **drop
`flyway_schema_history` too** — otherwise Flyway still believes the schema is at
version 1, creates nothing, and the app fails start-up with a missing table.
`drop schema public cascade; create schema public;` takes everything at once.

**The database is Postgres, not H2.** The code does not care: every query is
written in JPQL, so Hibernate produces the right SQL for each database. This was
checked before deployment by running the whole app against the PostgreSQL
dialect; every page rendered with no SQL errors.

**Region.** `render.yaml` uses Singapore, the closest Render region to India. To
change it, edit the `region` field for both the service and the database.

**Turning on real email (Section 16 #1).** Off by default: the hosted copy sends no
mail at all until you configure it, exactly like every other Render deployment of
this project so far. To turn it on, open the web service's **Environment** tab and:

1. Fill in `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME` and `SMTP_PASSWORD` with a
   real mail provider's details (for example an app password from a Gmail
   account, or an API-key-as-password from a transactional mail service).
   `render.yaml` lists these with `sync: false` so Render prompts for them here
   instead of a value ever being written into the repository.
2. Optionally set `MAIL_FROM` if the provider's required "From" address is
   different from `SMTP_USERNAME`.
3. Set `MAIL_ENABLED` to `true` and save. Render redeploys automatically.

If `MAIL_ENABLED` is `true` but the SMTP values are missing or wrong, the app
fails to start (the same fail-fast behaviour as a missing database column above)
rather than silently pretending to send mail - so Render keeps the previous,
working version running instead of going live half-configured.
