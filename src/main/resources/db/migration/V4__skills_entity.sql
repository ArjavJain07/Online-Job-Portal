-- ===========================================================================
-- V4 - skills as a first-class entity (Section 10.8)
--
-- Creates the shared `skills` vocabulary and the two join tables, and - the
-- part that actually matters - MOVES THE EXISTING DATA INTO THEM. The live
-- databases hold real comma-separated values right now: every job's required
-- skills and every candidate's own list of what they can do. Creating three
-- empty tables and calling the feature done would silently empty the
-- recommendation engine and every skill chip on the site on the first deploy.
-- So the second half of this file parses those columns and backfills.
--
-- THE OLD COLUMNS ARE KEPT, DELIBERATELY
-- jobs.skills and seeker_profiles.skills are untouched by this migration and
-- stay in the schema. The alternative - dropping them here, which is tidier -
-- makes the deploy one-way: the moment this runs, the previous release can no
-- longer read anybody's skills, and "roll back to the last version" stops
-- being an option for the rest of the release's life. Given that this is the
-- largest structural change the schema has had since V1, keeping the way back
-- open for one release is worth a redundant column.
--
-- Drift is what makes a kept column dangerous, so the entity mapping removes
-- the danger rather than promising to be careful: NOTHING in the application
-- reads those columns any more (domain.Job and domain.SeekerProfile expose no
-- getter for them), and the single method that changes a job's or a profile's
-- skills writes the relation and the column together. A column nothing reads
-- cannot drift into a wrong answer - at worst it is stale, which is exactly
-- what a rollback artefact is allowed to be.
--
-- This migration deliberately does NOT rewrite those columns to match the
-- labels it chose. A row that has not been saved since keeps the exact text
-- its employer or candidate typed.
--
-- ROLLING BACK
-- Deploy the previous release and run nothing. Flyway's history table keeps a
-- V4 row, which the older application ignores; the three new tables sit unused;
-- jobs.skills and seeker_profiles.skills are populated and correct for every
-- row that existed before the upgrade, and for every row saved after it. The
-- only thing lost is skill edits made through the new release on rows that...
-- no: those too, because assignSkills writes the column on every save. Nothing
-- is lost. Re-upgrading later simply finds V4 already applied and, since the
-- new release keeps both sides in step, needs no re-backfill.
--
-- NORMALISATION RULES USED BELOW (the same ones util.SkillParser and
-- util.TextMatcher apply to newly typed input, so migrated and typed skills
-- land on the same rows):
--   * split on commas;
--   * a token that is blank after trimming is dropped - and nothing else is;
--   * label   = the token trimmed with runs of whitespace collapsed to one
--               space, i.e. the spelling as typed;
--   * slug    = label lower-cased with every character other than a letter,
--               digit, + or # replaced by a space and runs collapsed. So
--               "Node.js", "node js" and "NODE-JS" are ONE skill, while "c",
--               "c++" and "c#" stay three;
--   * duplicates within one job or profile are removed by slug, first
--     occurrence kept, so "Node.js, node js" becomes one skill;
--   * where several spellings share a slug across the whole corpus, the one
--     used by the most rows wins and becomes that skill's label (ties broken
--     alphabetically, so the outcome is reproducible). A job that spelled it
--     "JAVA" therefore renders "Java" afterwards - its own column still says
--     "JAVA".
--
-- WHAT IS NOT NORMALISED, ON PURPOSE
--   * Near-synonyms. "JS" and "JavaScript", "Postgres" and "PostgreSQL",
--     "RDBMS" and "SQL" stay separate skills. Folding them needs a curated
--     dictionary, and a single wrong entry silently rewrites what a candidate
--     said about themselves. Punctuation and spacing variants are merged
--     because the application already treats them as the same phrase (case E7
--     of Section 7.8); judgements about meaning are left to people.
--   * The 30-skill cap. SkillParser stops at 30 when someone types a list into
--     a form. Applying it here would delete skill 31 from a profile that
--     somehow has one, which is data loss to enforce a form-entry limit.
--   * Garbage. A token of pure punctuation ("---") is not dropped: it becomes
--     a skill of its own with that text as both label and slug. It is inert -
--     nothing will ever match it - and it is already on screen as a chip
--     today, so keeping it changes nothing a user sees. Dropping it would mean
--     this migration deciding, with no way to ask, that a value a person typed
--     was meaningless. The guard at the end of this file depends on that rule:
--     because nothing non-blank is ever dropped, "a row with skills that
--     produced no join rows" is unambiguously a bug, and the migration stops.
--
-- PORTABILITY
-- Same one-directory-for-both-databases rule as V1 and V2 (Section 10.7).
-- Every construct here - WITH RECURSIVE, POSITION(x IN y), SUBSTRING(s FROM a
-- FOR b), REGEXP_REPLACE(s, re, r, 'g'), ROW_NUMBER() OVER (...) - is standard
-- SQL that H2 2.x and PostgreSQL 14 both implement, and the patterns avoid the
-- one place their regex engines differ in a way that matters here: the classes
-- used are spelled out ([^a-z0-9+#], \s) rather than POSIX names such as
-- [:alnum:], which Java's engine behind H2 does not accept.
--
-- The one known limit of the character class: it is Latin-oriented, matching
-- what TextMatcher computes for any skill written in ASCII letters, digits, +
-- and # - which every skill in this corpus is. A token written in another
-- script would canonicalise to an empty string, and the COALESCE below catches
-- exactly that case and falls back to the lower-cased text, which is what
-- TextMatcher would produce for it too. Such a token is preserved either way.
--
-- New constraints are named properly (fk_job_skills_job and friends) rather
-- than left to Hibernate's generated hashes - Section 10.7's rule for every
-- migration from V2 onward. The column lists and key orders below match
-- Hibernate's own generated DDL exactly, because BaselineSchemaTest compares
-- the two.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- The shared vocabulary. `slug` is the identity and is unique; `label` is what
-- gets rendered. Both are varchar(400), the width of the columns they replace:
-- one entry in jobs.skills could in principle be 400 characters long, and a
-- narrower column here would force this migration to choose between failing
-- and truncating somebody's skill. See domain.Skill for the same note.
-- ---------------------------------------------------------------------------
create table skills (
    id    bigint generated by default as identity,
    slug  varchar(400) not null unique,
    label varchar(400) not null,
    primary key (id)
);


-- ---------------------------------------------------------------------------
-- The two join tables. display_order carries the order the skills were typed
-- in, which is what the chips on a job page and the "Matches your skills: ..."
-- line show; it is part of the primary key because that is how Hibernate maps
-- an ordered @ManyToMany (an @OrderColumn list is keyed by owner + position,
-- not by owner + target).
-- ---------------------------------------------------------------------------
create table job_skills (
    display_order integer not null,
    job_id        bigint not null,
    skill_id      bigint not null,
    primary key (display_order, job_id)
);

create table seeker_profile_skills (
    display_order     integer not null,
    seeker_profile_id bigint not null,
    skill_id          bigint not null,
    primary key (display_order, seeker_profile_id)
);

-- No ON DELETE clause, following V1: deletion order is the application's job,
-- enforced in the services (Section 5.8).
alter table job_skills
    add constraint fk_job_skills_job foreign key (job_id) references jobs;

alter table job_skills
    add constraint fk_job_skills_skill foreign key (skill_id) references skills;

alter table seeker_profile_skills
    add constraint fk_seeker_profile_skills_profile foreign key (seeker_profile_id) references seeker_profiles;

alter table seeker_profile_skills
    add constraint fk_seeker_profile_skills_skill foreign key (skill_id) references skills;


-- ===========================================================================
-- BACKFILL
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Staging table, dropped at the end of this migration. One row per skill a job
-- or a profile lists, with the position it was typed at, its display spelling
-- and its canonical key. A real table rather than repeating the recursive CTE
-- in each of the four statements that need it: one definition of "how the old
-- text was parsed" is the whole point.
-- ---------------------------------------------------------------------------
create table v4_skill_tokens (
    owner_kind varchar(10)  not null,
    owner_id   bigint       not null,
    seq        integer      not null,
    label      varchar(400) not null,
    slug       varchar(400) not null
);

insert into v4_skill_tokens (owner_kind, owner_id, seq, label, slug)
with recursive
-- Both source columns, read the same way. COALESCE because
-- seeker_profiles.skills is nullable (a seeker who never filled it in).
sources (owner_kind, owner_id, csv) as (
    select cast('JOB' as varchar(10)), j.id, coalesce(j.skills, '') from jobs j
    union all
    select cast('SEEKER' as varchar(10)), p.id, coalesce(p.skills, '') from seeker_profiles p
),
-- Comma splitting, portably. A trailing comma is appended so every entry -
-- including the last - is terminated, and the walk stops when nothing is left.
-- The anchor row carries a NULL token (it has not consumed anything yet) and
-- is filtered out below; `seq` then numbers the real entries from 1.
split (owner_kind, owner_id, seq, token, remainder) as (
    select s.owner_kind, s.owner_id, 0,
           cast(null as varchar(400)),
           cast(s.csv || ',' as varchar(500))
      from sources s
    union all
    select w.owner_kind, w.owner_id, w.seq + 1,
           cast(substring(w.remainder from 1 for position(',' in w.remainder) - 1) as varchar(400)),
           cast(substring(w.remainder from position(',' in w.remainder) + 1) as varchar(500))
      from split w
     where w.remainder <> ''
),
-- The two normalisation rules of the header, in SQL. The COALESCE is the
-- "never drop anything non-blank" rule: when the canonical form comes out
-- empty - pure punctuation, or a script the character class does not know -
-- the lower-cased text is used as the key instead of the row disappearing.
cleaned (owner_kind, owner_id, seq, label, slug) as (
    select owner_kind, owner_id, seq,
           trim(regexp_replace(token, '\s+', ' ', 'g')),
           coalesce(
               nullif(trim(regexp_replace(lower(token), '[^a-z0-9+#]+', ' ', 'g')), ''),
               trim(regexp_replace(lower(token), '\s+', ' ', 'g')))
      from split
     where token is not null
       and trim(token) <> ''
),
-- Duplicates within one job or profile, by canonical key, first spelling kept.
deduped (owner_kind, owner_id, seq, label, slug, rank_in_owner) as (
    select owner_kind, owner_id, seq, label, slug,
           row_number() over (partition by owner_kind, owner_id, slug order by seq)
      from cleaned
)
select owner_kind, owner_id, seq, label, slug
  from deduped
 where rank_in_owner = 1;


-- ---------------------------------------------------------------------------
-- One row per distinct canonical key, labelled with the spelling the most rows
-- used. The inner query counts each (slug, label) pair; the outer one picks the
-- winner per slug, breaking ties alphabetically so two runs of this migration
-- on the same data always choose the same label.
-- ---------------------------------------------------------------------------
insert into skills (slug, label)
select slug, label
  from (
        select slug, label,
               row_number() over (partition by slug order by uses desc, label asc) as rank_for_slug
          from (
                select slug, label, count(*) as uses
                  from v4_skill_tokens
                 group by slug, label
               ) counted
       ) winners
 where rank_for_slug = 1;


-- ---------------------------------------------------------------------------
-- The join rows. display_order is renumbered from 0 per owner rather than
-- reusing `seq`: seq counts the raw comma-separated entries, so a list with a
-- blank or duplicate entry in the middle would leave a hole, and an
-- @OrderColumn list with a hole loads back with a null element in it.
-- ---------------------------------------------------------------------------
insert into job_skills (job_id, skill_id, display_order)
select t.owner_id, s.id, row_number() over (partition by t.owner_id order by t.seq) - 1
  from v4_skill_tokens t
  join skills s on s.slug = t.slug
 where t.owner_kind = 'JOB';

insert into seeker_profile_skills (seeker_profile_id, skill_id, display_order)
select t.owner_id, s.id, row_number() over (partition by t.owner_id order by t.seq) - 1
  from v4_skill_tokens t
  join skills s on s.slug = t.slug
 where t.owner_kind = 'SEEKER';


-- ---------------------------------------------------------------------------
-- Guards. Losing a candidate's skills is the one outcome this migration is not
-- allowed to have, and a backfill that quietly moved nothing looks exactly like
-- a backfill that worked until somebody opens the site. So the invariant is
-- asserted here instead of being hoped for: every row whose old column held
-- something other than whitespace must now have at least one join row.
--
-- It is written as an INSERT of NULL into a NOT NULL column because that is the
-- only way a plain-SQL migration can fail on purpose on both H2 and PostgreSQL.
-- The SELECT always returns exactly one row: 1 when the invariant holds, NULL
-- when it does not, which aborts the statement, rolls the whole migration back
-- (both databases run a migration in one transaction) and stops the deploy with
-- the previous version still serving - Section 10.7's "a stopped deploy is the
-- correct outcome".
-- ---------------------------------------------------------------------------
create table v4_backfill_guard (
    checked integer not null
);

insert into v4_backfill_guard (checked)
select case when count(*) = 0 then 1 else null end
  from jobs j
 where coalesce(trim(j.skills), '') <> ''
   and not exists (select 1 from job_skills js where js.job_id = j.id);

insert into v4_backfill_guard (checked)
select case when count(*) = 0 then 1 else null end
  from seeker_profiles p
 where coalesce(trim(p.skills), '') <> ''
   and not exists (select 1 from seeker_profile_skills ps where ps.seeker_profile_id = p.id);

drop table v4_backfill_guard;
drop table v4_skill_tokens;
