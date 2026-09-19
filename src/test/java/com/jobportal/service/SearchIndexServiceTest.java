package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Job;
import com.jobportal.domain.User;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.UserRepository;
import com.jobportal.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Proves that the search index BACKFILL works, not just that the columns exist (Section
// 10.9).
//
// WHY THIS NEEDS ITS OWN TEST AT ALL - the same blind spot SkillBackfillMigrationTest
// exists to cover, one migration later. Every other test in the suite runs against a
// database where DataSeeder created every row through the entity setters, so every row is
// already indexed by the time anything looks at it. SearchIndexService is therefore dead
// code as far as the rest of the suite is concerned: delete it and 500-odd tests still
// pass, and the first sign of trouble is a production database full of jobs that existed
// before V7 and that no keyword search can find.
//
// So these tests put a row back into the state a real upgrade leaves it in - V7's columns
// added, filled with the "never indexed" marker - and then check both halves: that such a
// row really is invisible to the search (which is what makes the backfill necessary), and
// that running the backfill makes it visible again (which is what makes it sufficient).
class SearchIndexServiceTest extends IntegrationTestBase {

    @Autowired
    private SearchIndexService searchIndexService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void backfillIndexesAJobThatPredatesTheSearchIndex() {
        Long jobId = data.jobId("Java Developer");
        markJobUnindexed(jobId);

        // The state a pre-V7 row is in on the morning of the upgrade: its prose is findable
        // by nothing. "developer" is in this job's title and description and in none of its
        // skills, so it can only be answered by the index.
        //
        // Note WHICH way it fails - the job goes missing from results it belongs in, it is
        // never wrongly present in someone else's. '' is LIKE nothing, so an unindexed row
        // can only ever cost a result, not produce a wrong one. That is the trade Section
        // 10.9 accepts for leaving the backfill out of the migration.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("developer")))
                .extracting(Job::getId).doesNotContain(jobId);

        // Its SKILLS still match, because they are rows rather than a cache: the skills
        // branch (Section 10.8) does not read the index and is untouched by any of this.
        // Half a search still working is better than none, and is worth knowing about.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("java")))
                .extracting(Job::getId).contains(jobId);

        assertThat(searchIndexService.reindexMissing()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(jobRepository.findById(jobId).orElseThrow().needsSearchIndex()).isFalse();
        assertThat(jobRepository.findAll(JobSpecifications.keyword("developer")))
                .extracting(Job::getId).contains(jobId);
        // Indexed from the job's own text by the same setters an edit uses, so the
        // whole-word rule holds for a backfilled row exactly as it does for a saved one.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("develop")))
                .extracting(Job::getId).doesNotContain(jobId);
    }

    @Test
    void backfillIndexesAnEmployerCompanyNameThatPredatesTheSearchIndex() {
        Long employerId = data.userId("hr@acme.local");
        Long jobId = data.jobId("Java Developer");
        markCompanyNameUnindexed(employerId);

        // Java Developer's own title, description and skills never say "Acme", so it can
        // only be found by that word through the company-name branch.
        assertThat(jobRepository.findAll(JobSpecifications.keyword("acme")))
                .extracting(Job::getId).doesNotContain(jobId);

        assertThat(searchIndexService.reindexMissing()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(employerId).orElseThrow().needsSearchIndex()).isFalse();
        assertThat(jobRepository.findAll(JobSpecifications.keyword("acme")))
                .extracting(Job::getId).contains(jobId);
    }

    // Idempotence is what makes running this on every start-up free instead of a one-off
    // someone has to remember to delete. The seeded database is fully indexed already, so
    // the first call has nothing to do either - which is the ordinary case on every start
    // after the upgrade.
    @Test
    void backfillDoesNothingWhenEveryRowIsAlreadyIndexed() {
        assertThat(searchIndexService.reindexMissing()).isZero();

        markJobUnindexed(data.jobId("QA Engineer"));
        assertThat(searchIndexService.reindexMissing()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(searchIndexService.reindexMissing())
                .as("a row that has just been indexed no longer looks unindexed")
                .isZero();
    }

    // A job seeker has no company name, so their NULL index must not look like work to do -
    // otherwise the backfill would load and rewrite every non-employer in the database on
    // every start-up, for ever, to achieve nothing.
    @Test
    void usersWithNoCompanyNameAreNotMistakenForUnindexedOnes() {
        User priya = userRepository.findById(data.userId("priya@demo.local")).orElseThrow();
        assertThat(priya.getCompanyName()).isNull();
        assertThat(priya.needsSearchIndex()).isFalse();

        assertThat(userRepository.findNeedingSearchIndex()).isEmpty();
    }

    // Puts a job back into the state V7 leaves a pre-existing row in. A native UPDATE, not
    // the entity, precisely because the entity cannot express this state: the setters
    // always write a real index, which is the property the whole design rests on.
    private void markJobUnindexed(Long jobId) {
        entityManager.createNativeQuery(
                        "update jobs set search_title = '', search_description = '' where id = :id")
                .setParameter("id", jobId)
                .executeUpdate();
        entityManager.clear();
    }

    private void markCompanyNameUnindexed(Long userId) {
        entityManager.createNativeQuery("update users set search_company_name = null where id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        entityManager.clear();
    }
}
