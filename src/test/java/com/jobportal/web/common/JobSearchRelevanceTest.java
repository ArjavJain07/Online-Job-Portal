package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Job;
import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.SystemSettingsRepository;
import com.jobportal.support.IntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Relevance ranking on GET /jobs (Section 10.9): what order results come back in, why,
// and what the page says about it.
//
// Every assertion here runs on H2, which is the point. The ranking is arithmetic over CASE
// expressions and LIKE - ordinary SQL:2003 that H2 and PostgreSQL 14 spell the same way -
// specifically so that the ordering a visitor will see in production is the ordering this
// test observes on a developer's machine. A tsvector/ts_rank implementation would have made
// this file impossible to write.
class JobSearchRelevanceTest extends IntegrationTestBase {

    // The job title as fragments/job-card renders it, which is the only place a title
    // appears once per result in result order (the detail pane repeats one of them in an
    // <h2>, and the filter panel repeats none).
    private static final Pattern CARD_TITLE = Pattern.compile("class=\"job-title\">([^<]+)<");

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private Clock clock;

    // THE HEADLINE REQUIREMENT. Searching "java" over the seeded data gives three Live
    // jobs, and the one CALLED "Java Developer" comes first - not the newest one.
    //
    // The scores, which are the whole explanation:
    //   Java Developer     title 100 + skills 60 + description 10 = 170
    //   Spring Boot Intern            skills 60 + description 10 =  70, approved 5 days ago
    //   QA Engineer                   skills 60 + description 10 =  70, approved 10 days ago
    // The two 70s tie, so the existing "newest" order breaks the tie, which is why Spring
    // Boot Intern is above QA Engineer.
    //
    // Frontend Developer is absent, and that is the other half of the fix: it lists HTML,
    // CSS, JavaScript and React - no Java - and only ever matched because its description
    // says "JavaScript" and the description was a substring search.
    @Test
    void titleMatchesOutrankSkillAndDescriptionMatches() throws Exception {
        assertThat(resultTitles(searchBody("/jobs?q=java")))
                .containsExactly("Java Developer", "Spring Boot Intern", "QA Engineer");
    }

    // One title match beats any number of lesser ones, which is what makes the ranking
    // explainable rather than merely plausible: a reader can say "it is first because the
    // word is in its title" without having to add anything up. The fixture is built for
    // exactly this - a job whose skills, company name and description ALL match (60 + 30 +
    // 10 = 100) still sits below one where only the title does.
    @Test
    void oneTitleMatchOutranksEveryLesserMatchCombined() throws Exception {
        Job titleOnly = saveJob("Kotlin Engineer", "Build services for our payments team.", "Docker",
                LocalDateTime.now(clock).minusDays(60));
        Job everythingElse = saveJob("Backend Engineer",
                "You will write Kotlin every day for Acme Technologies.", "Kotlin",
                LocalDateTime.now(clock).minusDays(1));

        List<String> order = resultTitles(searchBody("/jobs?q=kotlin"));
        assertThat(order).containsExactly("Kotlin Engineer", "Backend Engineer");
        // ...and it is not simply the older job winning: relevance, not date, put it there.
        assertThat(titleOnly.getApprovedAt()).isBefore(everythingElse.getApprovedAt());
    }

    // Ranking is stable across pages: the order is applied to the whole result set by the
    // query, not to each page after the fact, so paging through it neither repeats nor
    // drops a job - and the count beside it still counts the right rows.
    @Test
    void relevanceOrderHoldsAcrossPages() throws Exception {
        setPageSize(2);

        String firstPage = searchBody("/jobs?q=java&page=0");
        assertThat(firstPage).contains("Showing 1-2 of 3");
        assertThat(resultTitles(firstPage)).containsExactly("Java Developer", "Spring Boot Intern");

        String secondPage = searchBody("/jobs?q=java&page=1");
        assertThat(secondPage).contains("Showing 3-3 of 3");
        assertThat(resultTitles(secondPage)).containsExactly("QA Engineer");
    }

    // Two jobs that score identically must come back in a fixed order every time, or page 2
    // can repeat what page 1 showed. The third key is the id, which is unique, so no tie can
    // survive to be broken arbitrarily by the database. Both jobs below match only through
    // their skills and are approved at the same instant, so the first two keys are exhausted.
    @Test
    void jobsThatScoreAndDateIdenticallyAreStillOrderedDeterministically() throws Exception {
        LocalDateTime sameInstant = LocalDateTime.now(clock).minusDays(3);
        Job first = saveJob("Platform Engineer", "Work on our internal tooling.", "Rust", sameInstant);
        Job second = saveJob("Systems Engineer", "Work on our internal tooling.", "Rust", sameInstant);

        List<String> order = resultTitles(searchBody("/jobs?q=rust"));
        assertThat(order).containsExactly("Systems Engineer", "Platform Engineer");
        assertThat(second.getId()).isGreaterThan(first.getId());

        // Repeating the request gives the same answer - the point of a deterministic third
        // key is that nothing is left for the database to decide.
        assertThat(resultTitles(searchBody("/jobs?q=rust"))).isEqualTo(order);
    }

    // EXPLAINABILITY (Section 10.9). Each card says where the typed words were found, in
    // ranking order, so the first place named is the one that decided the job's position.
    // The line is computed in Java by TextMatcher.containsPhrase over the same two strings
    // the database was handed, so it cannot name a place the query did not match.
    @Test
    void eachResultSaysWhyItIsThere() throws Exception {
        String body = searchBody("/jobs?q=java");

        assertThat(cardFor(body, "Java Developer")).contains("Matched in title, skills and description");
        assertThat(cardFor(body, "Spring Boot Intern")).contains("Matched in skills and description");
    }

    // A job found only through its employer's name says so, which is the case a visitor is
    // least likely to work out for themselves - the word they typed is nowhere in the
    // card's title or description. Java Developer is the right job to check: of Acme's Live
    // postings it is one of the two whose own text never names the company.
    @Test
    void aCompanyNameMatchIsExplainedAsOne() throws Exception {
        String body = searchBody("/jobs?q=acme");

        assertThat(cardFor(body, "Java Developer")).contains("Matched in company name");
    }

    // With no keyword there is nothing to explain, so nothing is printed. The same fragment
    // renders the landing page and the recommendations list, which never pass the map at
    // all.
    @Test
    void noKeywordMeansNoExplanationAndNoRelevanceSort() throws Exception {
        String body = searchBody("/jobs");

        assertThat(body).doesNotContain("Matched in");
        // Unchanged default: newest first (Spring Boot Intern, approved 5 days ago).
        assertThat(resultTitles(body)).startsWith("Spring Boot Intern");
    }

    // The sort dropdown has to agree with what actually happened, or the page lies about
    // itself. "Relevance" is pre-selected exactly when there is a keyword; "Newest" when
    // there is not.
    @Test
    void theSortDropdownShowsWhichOrderWasUsed() throws Exception {
        assertThat(selectedSortOption(searchBody("/jobs?q=java"))).isEqualTo("relevance");
        assertThat(selectedSortOption(searchBody("/jobs"))).isEqualTo("newest");
        assertThat(selectedSortOption(searchBody("/jobs?q=java&sort=newest"))).isEqualTo("newest");
        assertThat(selectedSortOption(searchBody("/jobs?q=java&sort=salary"))).isEqualTo("salary");
    }

    // Relevance is a default, not a lock-in: asking for one of the existing orders still
    // gets it. Under "newest" the same three jobs come back oldest-last, which is the exact
    // order this page showed before Section 10.9.
    @Test
    void anExplicitSortStillWins() throws Exception {
        assertThat(resultTitles(searchBody("/jobs?q=java&sort=newest")))
                .containsExactly("Spring Boot Intern", "QA Engineer", "Java Developer");
    }

    // "relevance" asked for with no keyword cannot be honoured - there is nothing to rank
    // against - so it falls back to "newest" rather than producing an error page, per
    // Section 7.9's lenient binding rule. Notably it must NOT emit an order-by over a
    // constant: `order by 0 desc` is a positional reference on PostgreSQL and would fail
    // there while passing here.
    @Test
    void relevanceWithoutAKeywordFallsBackToNewest() throws Exception {
        assertThat(resultTitles(searchBody("/jobs?sort=relevance")))
                .isEqualTo(resultTitles(searchBody("/jobs?sort=newest")));
    }

    // A search phrase that normalises away to nothing matches no job - and above all does
    // not quietly become "every job on the site". The relevance branch must survive it too,
    // since sort=relevance is still what such a request asks for.
    @Test
    void aPunctuationOnlySearchMatchesNothing() throws Exception {
        String body = searchBody("/jobs?q=%25%25%25");

        assertThat(body).contains("0 jobs found");
        assertThat(resultTitles(body)).isEmpty();
    }

    // The skill facet (Section 10.8) is counted from the search BEFORE its own skill filter
    // and must keep working untouched beside a relevance-ordered result list - the two are
    // built from the same Specification, and relevanceOrder is deliberately chained on
    // afterwards so it cannot reach the facet query.
    @Test
    void skillFacetCountsAreUnaffectedByRelevanceOrdering() throws Exception {
        String body = searchBody("/jobs?q=java");

        // All three matching jobs list Java; only two of them list Spring Boot.
        assertThat(facetCount(body, "Java")).isEqualTo(3);
        assertThat(facetCount(body, "Spring Boot")).isEqualTo(2);
    }

    // ---- helpers ----

    private String searchBody(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // The titles of the result cards, in the order they are rendered.
    private List<String> resultTitles(String body) {
        List<String> titles = new ArrayList<>();
        Matcher matcher = CARD_TITLE.matcher(body);
        while (matcher.find()) {
            titles.add(matcher.group(1));
        }
        return titles;
    }

    // One job's card markup, isolated the same way JobSearchTest#assertAppliedBadge does
    // it: split the page on the card's own opening class string rather than taking a
    // fixed-size window that could spill into the next card.
    private String cardFor(String body, String jobTitle) {
        for (String candidate : body.split("card shadow-sm mb-3")) {
            if (candidate.contains(jobTitle)) {
                return candidate;
            }
        }
        throw new AssertionError("No job card found for " + jobTitle);
    }

    // The value of whichever <option> of the sort dropdown carries "selected".
    private String selectedSortOption(String body) {
        int start = body.indexOf("id=\"filter-sort\"");
        assertThat(start).as("sort dropdown present").isPositive();
        String select = body.substring(start, body.indexOf("</select>", start));
        Matcher matcher = Pattern.compile("<option value=\"([a-z]+)\" selected").matcher(select);
        assertThat(matcher.find()).as("one option is selected").isTrue();
        return matcher.group(1);
    }

    // The number in a "Java (3)" facet chip, which fragments/job-filters renders as the
    // label span followed by the count span.
    private int facetCount(String body, String label) {
        Matcher matcher = Pattern.compile(">" + Pattern.quote(label) + "</span>\\s*<span[^>]*>\\((\\d+)\\)")
                .matcher(body);
        assertThat(matcher.find()).as("facet chip for %s", label).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    // A Live job with the given text and skills, approved at the given instant so the
    // date tie-break can be controlled.
    private Job saveJob(String title, String description, String skills, LocalDateTime approvedAt) {
        Job job = new Job();
        job.setEmployer(data.user("hr@acme.local"));
        job.setTitle(title);
        job.setDescription(description);
        job.setRequirements("Nothing in particular.");
        job.assignSkills(data.skills(skills));
        job.setCategory(JobCategory.SOFTWARE_DEVELOPMENT);
        job.setJobType(JobType.FULL_TIME);
        job.setWorkMode(WorkMode.HYBRID);
        job.setLocation("Pune");
        job.setSalaryMin(500_000);
        job.setSalaryMax(900_000);
        job.setMinExperienceYears(0);
        job.setOpenings(1);
        job.setApplicationDeadline(LocalDate.now(clock).plusDays(30));
        job.setStatus(JobStatus.APPROVED);
        job.setCreatedAt(approvedAt);
        job.setApprovedAt(approvedAt);
        return jobRepository.saveAndFlush(job);
    }

    private void setPageSize(int pageSize) {
        SystemSettings settings = systemSettingsRepository.findById(1L).orElseThrow();
        settings.setPageSize(pageSize);
        systemSettingsRepository.saveAndFlush(settings);
    }
}
