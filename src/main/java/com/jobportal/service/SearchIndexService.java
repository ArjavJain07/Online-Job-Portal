package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.User;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.UserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Fills in the search index columns V7 added, for rows that existed before it did
// (Section 10.9). Runs once at every start-up, like DataSeeder, and does nothing at all
// once there is nothing left to index.
//
// WHY THE BACKFILL IS HERE AND NOT IN V7
// This is the two-database decision of Section 10.9 in one class. The index is
// TextMatcher.normalise applied to three columns, and normalise is a character walk: lower
// the character, keep it if it is a letter, a digit, + or #, otherwise write a space, then
// collapse runs. Expressing that in portable SQL is possible - V4 does it - but V4 also
// records what it cost and why the obvious REGEXP_REPLACE could not be used: H2's replaces
// every match and rejects the 'g' flag, PostgreSQL's replaces only the first without it, so
// the one-line version canonicalises correctly on every machine the project is developed on
// and mangles the data on the one machine that matters. V4 paid for that with a recursive
// walk whose intermediate result is quadratic in the length of the text - affordable for a
// twelve-character skill, not for a four-thousand-character job description.
//
// Doing it in Java does not answer that portability question; it deletes it. There is no
// SQL dialect involved in normalisation on either database, because neither database
// normalises anything. The exact same compiled method produces the index on a developer's
// H2 file, in the H2 test suite, and on PostgreSQL 14 in production, and it is the same
// method JobSearchService uses to explain a result and RecommendationScorer has used since
// Section 7.8. Nothing can diverge because there is only one thing.
//
// What it costs: V7 leaves the columns empty, so between Flyway finishing and this class
// running - the same start-up, before the web server accepts a request - a keyword search
// would find fewer jobs than it should. It can never find the wrong ones, because '' is
// LIKE nothing. A missing result for a fraction of a second on one deploy is the cheaper
// half of that trade.
//
// WHY IT RE-RUNS THE SETTERS INSTEAD OF COMPUTING THE INDEX ITSELF
// Job.reindexSearchText() calls setTitle(getTitle()) and setDescription(getDescription()).
// That looks like a no-op and is the point: the index is defined by those setters, so the
// backfill cannot compute it differently from the way an ordinary edit does. A backfill
// with its own copy of the rule is a second definition, and a second definition of "what
// this job's index should be" is what would eventually leave the pre-10.9 half of the
// database findable under slightly different words from the half saved since.
@Service
public class SearchIndexService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public SearchIndexService(JobRepository jobRepository, UserRepository userRepository) {
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    // @Transactional sits HERE, on the entry point Spring itself calls, and not on
    // reindexMissing below. That is not a style choice. @Transactional is applied by a
    // proxy, and a call from one method of a bean to another - this.reindexMissing() -
    // does not go through the proxy, so annotating the inner method started no
    // transaction at all. The repositories then each opened and closed their own, the
    // entities came back DETACHED, and dirty checking had nothing to flush: the counter
    // still rose, the log still said "built 15 missing entries", and not one row was
    // written. Found in production, where every search column was empty and a search for
    // "developer" returned nothing.
    //
    // The saveAll calls below make that independent of proxying altogether. Either one
    // alone would fix it; both together mean this keeps working if someone later calls
    // reindexMissing directly, which is exactly how the original defect was hidden - the
    // unit test called it from inside a @Transactional test, where the entities happen to
    // be managed and dirty checking does work.
    @Override
    @Transactional
    public void run(String... args) {
        int indexed = reindexMissing();
        if (indexed > 0) {
            log.info("Search index: built {} missing entries (Section 10.9)", indexed);
        }
    }

    // Indexes every row that has never been indexed and returns how many were touched.
    // Idempotent: the two finders ask for rows still carrying the "not indexed" marker, and
    // a row that has just been indexed no longer carries it, so a second call returns 0 and
    // issues no updates. That is what makes running this on every start-up free rather than
    // something that has to be remembered once and then removed.
    //
    // Deliberately NOT paged. Both finders return only the rows a single upgrade left
    // behind - every row saved since is already indexed by its own setter - so this loads
    // the whole table exactly once in the life of a database, and never again. If a portal
    // ever grows to the point where that one pass is too big to hold, the fix is to page
    // this loop, not to move the rule back into SQL.
    @Transactional
    public int reindexMissing() {
        int count = 0;

        List<Job> jobs = jobRepository.findNeedingSearchIndex();
        for (Job job : jobs) {
            job.reindexSearchText();
            count++;
        }
        jobRepository.saveAll(jobs);

        List<User> employers = userRepository.findNeedingSearchIndex();
        for (User employer : employers) {
            employer.reindexSearchText();
            count++;
        }
        userRepository.saveAll(employers);

        return count;
    }
}
