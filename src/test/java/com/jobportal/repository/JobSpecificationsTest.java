package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Integration test for the JobSpecifications building blocks (Section 12.2), run against
// the real Section 13 seed data through JobRepository (JpaSpecificationExecutor) instead
// of a hand-built @DataJpaTest fixture, so the "live excludes ..." case can be checked
// against the exact jobs the acceptance criteria talk about.
class JobSpecificationsTest extends IntegrationTestBase {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private Clock clock;

    // 11.3 contract item 4: live(today) must match Job.isLive(today) exactly. The seed
    // data (13.4, 13.8) has exactly 6 Live jobs; every other stored status - pending,
    // rejected, closed - and every computed state - expired, hidden (disabled employer) -
    // must be excluded.
    @Test
    void liveExcludesPendingRejectedClosedExpiredAndHidden() {
        List<Job> liveJobs = jobRepository.findAll(JobSpecifications.live(LocalDate.now(clock)));

        assertThat(liveJobs).extracting(Job::getTitle).containsExactlyInAnyOrder(
                "Java Developer", "Spring Boot Intern", "Frontend Developer", "QA Engineer", "Data Analyst",
                "Marketing Executive");
    }

    // notAppliedBy alone, with no live() filter: a seeker with no applications (Karan
    // Singh) excludes nothing; Priya's 4 applications (Java Developer, Frontend
    // Developer, Data Analyst, Python Backend Developer - Section 13.5) are excluded from
    // every job, whether or not they are still Live.
    @Test
    void notAppliedByExcludesEveryJobTheSeekerHasAppliedTo() {
        Long karanId = data.userId("karan@demo.local");
        List<Job> forKaran = jobRepository.findAll(JobSpecifications.notAppliedBy(karanId));
        assertThat(forKaran).hasSize(12);

        Long priyaId = data.userId("priya@demo.local");
        List<Job> forPriya = jobRepository.findAll(JobSpecifications.notAppliedBy(priyaId));
        assertThat(forPriya).hasSize(8);
        assertThat(forPriya).extracting(Job::getTitle).doesNotContain(
                "Java Developer", "Frontend Developer", "Data Analyst", "Python Backend Developer");
    }

    // Combined with live(): of Priya's 3 Live applications, only the 3 Live jobs she has
    // not applied to remain (7.8 recommendation candidates use this same combination).
    @Test
    void liveAndNotAppliedByCombineForRecommendationCandidates() {
        Long priyaId = data.userId("priya@demo.local");
        List<Job> candidates = jobRepository.findAll(
                JobSpecifications.live(LocalDate.now(clock)).and(JobSpecifications.notAppliedBy(priyaId)));

        assertThat(candidates).extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Spring Boot Intern", "QA Engineer", "Marketing Executive");
    }

    // CHANGED BY SECTION 10.9, and the change is the correct one.
    //
    // This used to assert that keyword("%") returned the one job whose title contains a
    // literal "%", which is what a correctly escaped substring LIKE does. keyword() is no
    // longer a substring search: it matches whole words against the V7 index, and
    // SearchIndex.of turns every character that is not a letter, digit, + or # into a
    // space - so "%" and "_" are not words, they are whitespace, and the phrase a visitor
    // typed is empty. An empty phrase matches NOTHING, the same answer
    // TextMatcher.containsPhrase and the skills branch have always given for one.
    //
    // The danger the old test guarded against is unchanged and is still covered: an
    // unescaped "%" reaching a LIKE means one keystroke silently turns a filtered search
    // into "every job on the site". That is asserted here from the other side - the result
    // must be empty, not everything - and the escaping itself is still proved by
    // locationEscapesPercentAndUnderscore below, which covers the filter that really is
    // still a substring search over raw text.
    @Test
    void keywordIgnoresAPhraseThatIsOnlyPunctuation() {
        Job job = jobRepository.saveAndFlush(oddlyNamedJob());

        assertThat(jobRepository.findAll(JobSpecifications.keyword("%")))
                .as("\"%\" is not a word, so it must match nothing - and above all not everything")
                .isEmpty();
        assertThat(jobRepository.findAll(JobSpecifications.keyword("_"))).isEmpty();
        assertThat(jobRepository.findAll(JobSpecifications.keyword("---"))).isEmpty();

        // The job itself is still findable by the words in its title, so "matches nothing"
        // is about the phrase that was typed and not about the row.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("sale")))
                .extracting(Job::getId).containsExactly(job.getId());
    }

    // locationContains is still a substring search over the raw column - "pun" has to find
    // "Pune" - so it still builds a LIKE pattern from text a visitor typed, and still has
    // to escape it. Without escaping, "%" and "_" are SQL wildcards that would match almost
    // every seeded job, so matching only the one job that really contains the literal
    // character proves the escaping works (Section 7.9).
    @Test
    void locationEscapesPercentAndUnderscore() {
        Job job = plainJob();
        job.setLocation("50% Off_Sale Park");
        job.assignSkills(data.skills("Sales"));
        jobRepository.saveAndFlush(job);

        assertThat(jobRepository.findAll(JobSpecifications.locationContains("%")))
                .extracting(Job::getId).containsExactly(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.locationContains("_")))
                .extracting(Job::getId).containsExactly(job.getId());
    }

    // The defect that made skills an entity (Section 10.8): searching "Java" used to
    // return every job whose skills column merely CONTAINED that text, so a pure
    // JavaScript job came back for a Java search. The skills half of keyword() now matches
    // whole tokens against Skill.slug, so it does not - while "spring" still finds a job
    // whose skill is "Spring Boot", because a search box has to match part of a phrase.
    //
    // The fixture keeps the word out of the title, description, requirements and company
    // name, so a match can only have come through the skills branch. (When this test was
    // written no seeded job could be used for it: Frontend Developer lists JavaScript, but
    // its description says "JavaScript" too, and the prose columns were still a substring
    // search, so it came back for "java" anyway. Section 10.9 fixed that half as well -
    // see keywordDoesNotMatchAWordInsideAnotherWordInProse below, which now asserts on
    // that exact seeded job.)
    @Test
    void keywordMatchesSkillsAsWholeTokensNotSubstrings() {
        Job job = jobRepository.saveAndFlush(skilledJob("Front End Engineer", "JavaScript, Spring Boot"));

        assertThat(jobRepository.findAll(JobSpecifications.keyword("java")))
                .as("\"java\" must not match the skill \"JavaScript\"")
                .extracting(Job::getId)
                .doesNotContain(job.getId());

        assertThat(jobRepository.findAll(JobSpecifications.keyword("javascript")))
                .extracting(Job::getId).contains(job.getId());

        assertThat(jobRepository.findAll(JobSpecifications.keyword("spring")))
                .as("a word of a multi-word skill must still match")
                .extracting(Job::getId).contains(job.getId());

        assertThat(jobRepository.findAll(JobSpecifications.keyword("boot")))
                .as("including the last word")
                .extracting(Job::getId).contains(job.getId());
    }

    // THE DEFECT SECTION 10.9 EXISTS TO FIX, on the exact seeded job it was reported
    // against. Frontend Developer (J3) lists HTML, CSS, JavaScript and React - no Java at
    // all - and its description reads "build user interfaces with HTML, CSS and
    // JavaScript". Under the old substring LIKE over the description it came back for
    // "java" even though the skills branch had correctly excluded it, so the precision
    // Section 10.8 bought was worth nothing to anyone using the search box.
    //
    // Both directions are asserted, because "match less" is only right if it still matches
    // the jobs that really are about Java: the three seeded jobs that mention Java as a
    // word (J1 in its title, skills and description; J2 in its skills and description; J4
    // in its skills and description) must all still be found.
    @Test
    void keywordDoesNotMatchAWordInsideAnotherWordInProse() {
        List<Job> matches = jobRepository.findAll(
                JobSpecifications.live(LocalDate.now(clock)).and(JobSpecifications.keyword("java")));

        assertThat(matches).extracting(Job::getTitle)
                .as("\"java\" must not match the description's \"JavaScript\"")
                .doesNotContain("Frontend Developer");
        assertThat(matches).extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Java Developer", "Spring Boot Intern", "QA Engineer");

        // And the JavaScript job is still findable by its own name, in prose as well as in
        // its skills - this is a precision fix, not a narrower index.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("javascript")))
                .extracting(Job::getTitle).contains("Frontend Developer");
    }

    // Whole-word matching over prose has to be punctuation-tolerant, or it trades one kind
    // of wrong answer for another: a description that ends a sentence with the word, or
    // writes it as "Node.js", still contains that word. SearchIndex.of normalises the
    // stored text the same way TextMatcher does, so all three spellings below land on the
    // same token - while "java" still fails to reach inside "JavaScript".
    @Test
    void keywordMatchesWholeWordsThroughPunctuationAndCase() {
        Job job = plainJob();
        job.setTitle("Platform Engineer");
        job.setDescription("You will own our Node.js services. Deployment is automated; JavaScript everywhere.");
        job.assignSkills(data.skills("Docker"));
        jobRepository.saveAndFlush(job);

        assertThat(jobRepository.findAll(JobSpecifications.keyword("node js")))
                .as("\"Node.js\" and \"node js\" are the same word")
                .extracting(Job::getId).contains(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.keyword("NODE.JS")))
                .as("matching ignores case and punctuation")
                .extracting(Job::getId).contains(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.keyword("deployment")))
                .as("a word followed by a full stop is still that word")
                .extracting(Job::getId).contains(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.keyword("java")))
                .as("but a word inside another word is not")
                .extracting(Job::getId).doesNotContain(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.keyword("deploy")))
                .as("the accepted cost of the above: a prefix is no longer a match")
                .extracting(Job::getId).doesNotContain(job.getId());
    }

    // The company-name branch, which moved to the users table's own index (V7) rather than
    // being copied onto each job. Same whole-word rule: "Acme" finds Acme Technologies'
    // jobs, "Acm" finds nothing.
    @Test
    void keywordMatchesTheEmployersCompanyNameAsWholeWords() {
        assertThat(jobRepository.findAll(
                JobSpecifications.live(LocalDate.now(clock)).and(JobSpecifications.keyword("acme"))))
                .extracting(Job::getTitle)
                .contains("Java Developer", "Frontend Developer", "QA Engineer");
        assertThat(jobRepository.findAll(JobSpecifications.keyword("acm"))).isEmpty();
    }

    // Renaming a company has to move its jobs with it, which is the whole reason the index
    // lives on the user row and is written by User.setCompanyName rather than being
    // denormalised onto every job the employer has posted.
    // "Java Developer" and "QA Engineer" are the two Acme jobs whose own title, description
    // and skills never say "Acme" (J2, J3 and J5 all name the company in their opening
    // sentence), so they can only ever have matched through the company-name branch. After
    // the rename they must follow the new name and stop answering to the old one - which
    // is what a denormalised copy of the name on each job would have got wrong.
    @Test
    void renamingACompanyMovesItsJobsInTheSearch() {
        assertThat(jobRepository.findAll(JobSpecifications.keyword("acme")))
                .extracting(Job::getTitle).contains("Java Developer", "QA Engineer");

        User acme = data.user("hr@acme.local");
        acme.setCompanyName("Northwind Systems");
        userRepository.saveAndFlush(acme);

        assertThat(jobRepository.findAll(JobSpecifications.keyword("northwind")))
                .extracting(Job::getTitle).contains("Java Developer", "QA Engineer");
        assertThat(jobRepository.findAll(JobSpecifications.keyword("acme")))
                .extracting(Job::getTitle).doesNotContain("Java Developer", "QA Engineer");
    }

    // The same rule applied to the filter the facet chips use: hasSkill is exact identity,
    // so it never leaks a near-miss into a filtered page.
    @Test
    void hasSkillMatchesOnlyTheExactSkill() {
        Job job = jobRepository.saveAndFlush(skilledJob("Front End Engineer", "JavaScript"));

        // The seeded Frontend Developer lists JavaScript too, so this is "contains", not
        // "containsExactly" - what matters is that a "java" filter does not pick up a
        // JavaScript job, which the second assertion pins down.
        assertThat(jobRepository.findAll(JobSpecifications.hasSkill("javascript")))
                .extracting(Job::getId).contains(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.hasSkill("java")))
                .extracting(Job::getId).doesNotContain(job.getId());
        assertThat(jobRepository.findAll(JobSpecifications.hasSkill("java")))
                .extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Java Developer", "Spring Boot Intern", "QA Engineer");
    }

    // A job whose title literally contains "%" and "_" - no seeded job does, so any match
    // on these characters must come from this one job.
    private Job oddlyNamedJob() {
        Job job = plainJob();
        job.setTitle("50% Off_Sale Associate");
        job.assignSkills(data.skills("Sales"));
        return job;
    }

    // A job carrying the given skills and deliberately bland text, so a keyword match can
    // only have come from the skills.
    private Job skilledJob(String title, String skills) {
        Job job = plainJob();
        job.setTitle(title);
        job.assignSkills(data.skills(skills));
        return job;
    }

    private Job plainJob() {
        Job job = new Job();
        job.setEmployer(data.user("hr@acme.local"));
        job.setTitle("Placeholder");
        job.setDescription("Seasonal sales role.");
        job.setRequirements("Retail experience preferred.");
        job.setCategory(JobCategory.SALES);
        job.setJobType(JobType.CONTRACT);
        job.setWorkMode(WorkMode.ONSITE);
        job.setLocation("Pune");
        job.setSalaryMin(200_000);
        job.setSalaryMax(300_000);
        job.setMinExperienceYears(0);
        job.setOpenings(1);
        job.setApplicationDeadline(LocalDate.now(clock).plusDays(30));
        job.setStatus(JobStatus.APPROVED);
        job.setCreatedAt(LocalDateTime.now(clock));
        return job;
    }
}
