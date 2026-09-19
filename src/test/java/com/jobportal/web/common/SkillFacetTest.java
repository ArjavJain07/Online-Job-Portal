package com.jobportal.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobportal.domain.Job;
import com.jobportal.dto.JobSearchResult;
import com.jobportal.dto.SkillFacet;
import com.jobportal.service.JobSearchService;
import com.jobportal.support.IntegrationTestBase;
import com.jobportal.web.form.JobSearchCriteria;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// The faceted skill filter on job search (Section 10.8) - the feature that was impossible
// while skills were a comma-separated column, because "how many jobs ask for Java" cannot
// be counted from text without also counting every job that asks for JavaScript.
//
// The seeded Live jobs (Section 13.4) and the skills they list:
//   Java Developer        Java, Spring Boot, SQL, Git
//   Spring Boot Intern    Java, Spring Boot
//   Frontend Developer    HTML, CSS, JavaScript, React
//   QA Engineer           Selenium, Java, Testing
//   Data Analyst          SQL, Excel, Python, Power BI
//   Marketing Executive   SEO, Social Media, Content Writing
// so Java is on 3 of the 6, SQL and Spring Boot on 2 each, everything else on 1.
//
// Nothing here asserts the order of the one-per-skill tail. Ties are broken by label, and
// two databases with different collations disagree about whether "CSS" sorts before
// "Content Writing" - a real difference, but not one the feature depends on.
class SkillFacetTest extends IntegrationTestBase {

    @Autowired
    private JobSearchService jobSearchService;

    @Test
    void facetCountsTheLiveJobsThatListEachSkill() {
        List<SkillFacet> facets = jobSearchService.search(criteria(null, null)).skillFacets();

        assertThat(count(facets, "java")).isEqualTo(3);
        assertThat(count(facets, "sql")).isEqualTo(2);
        assertThat(count(facets, "spring boot")).isEqualTo(2);
        assertThat(count(facets, "git")).isEqualTo(1);

        // Most-used first, so the chip worth clicking is the first one.
        assertThat(facets.get(0).slug()).isEqualTo("java");
        assertThat(facets.get(0).label()).isEqualTo("Java");

        // The counts are of jobs, not of rows: nothing is inflated by a job listing
        // several skills.
        assertThat(facets).allMatch(facet -> facet.jobCount() <= 6);

        // A skill only a non-Live job asks for is not a facet at all. Docker is on the
        // pending DevOps Engineer job and nowhere else, so offering it would lead to an
        // empty page.
        assertThat(facets).noneMatch(facet -> facet.slug().equals("docker"));
    }

    @Test
    void selectingASkillNarrowsTheResultsToJobsThatListIt() {
        JobSearchResult result = jobSearchService.search(criteria("java", null));

        assertThat(result.jobs().getContent()).extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Java Developer", "Spring Boot Intern", "QA Engineer");
    }

    // The point of computing the facets BEFORE applying the skill filter: with the filter
    // applied, "Java (3)" would be the only row left and there would be no way to move to
    // another skill without first clearing this one.
    @Test
    void facetsStayAvailableWhileASkillIsSelected() {
        List<SkillFacet> facets = jobSearchService.search(criteria("java", null)).skillFacets();

        assertThat(count(facets, "java")).isEqualTo(3);
        assertThat(count(facets, "sql")).isEqualTo(2);
        assertThat(count(facets, "spring boot")).isEqualTo(2);
        // Skills only the other three Live jobs ask for are still offered, so the visitor
        // can move sideways from Java to something else in one click.
        assertThat(facets).anyMatch(facet -> facet.slug().equals("react") || facet.slug().equals("excel"));
        assertThat(facets).filteredOn(SkillFacet::selected).extracting(SkillFacet::slug).containsExactly("java");
    }

    // The facet composes with the other filters rather than replacing them, and the counts
    // describe the filtered set - "Java (2)" once the search is already narrowed to
    // internships and full-time software jobs, not the site-wide 3.
    @Test
    void facetCountsRespectTheOtherFiltersInTheSameSearch() {
        JobSearchCriteria criteria = criteria(null, null);
        criteria.setLocation("pune");

        List<SkillFacet> facets = jobSearchService.search(criteria).skillFacets();

        // Only Java Developer and Spring Boot Intern are Live in Pune.
        assertThat(count(facets, "java")).isEqualTo(2);
        assertThat(count(facets, "spring boot")).isEqualTo(2);
        assertThat(count(facets, "git")).isEqualTo(1);
        assertThat(facets).noneMatch(facet -> facet.slug().equals("seo"));

        criteria.setSkill("git");
        assertThat(jobSearchService.search(criteria).jobs().getContent()).extracting(Job::getTitle)
                .containsExactly("Java Developer");
    }

    // Section 7.9's lenient query-parameter rule. A link shared after the last job asking
    // for that skill came down must still show jobs, not an error and not an empty list.
    @Test
    void anUnknownSkillIsIgnoredLikeAnyOtherUnparsableFilter() {
        JobSearchResult result = jobSearchService.search(criteria("no-such-skill-anywhere", null));

        assertThat(result.criteria().skill()).isNull();
        assertThat(result.jobs().getTotalElements()).isEqualTo(6);
    }

    // The slug in the URL is canonical, so the spellings a person might type or a chip
    // might have been bookmarked under all resolve to the same filter.
    @Test
    void theSkillParameterIsCanonicalisedBeforeItIsUsed() {
        assertThat(jobSearchService.search(criteria("JAVA", null)).jobs().getTotalElements()).isEqualTo(3);
        assertThat(jobSearchService.search(criteria("  Java  ", null)).jobs().getTotalElements()).isEqualTo(3);
        assertThat(jobSearchService.search(criteria("Spring-Boot", null)).jobs().getTotalElements()).isEqualTo(2);
    }

    // The chips really reach the page, with their counts, and selecting one is a plain
    // link that keeps the rest of the query string (fragments/job-filters).
    @Test
    void chipsRenderOnThePublicJobsPageWithTheirCounts() throws Exception {
        mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("(3)")))
                .andExpect(content().string(Matchers.containsString("skill=java")));

        mockMvc.perform(get("/jobs").param("skill", "java"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("3 jobs found")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Marketing Executive"))));
    }

    private JobSearchCriteria criteria(String skill, String q) {
        JobSearchCriteria criteria = new JobSearchCriteria();
        criteria.setSkill(skill);
        criteria.setQ(q);
        return criteria;
    }

    private long count(List<SkillFacet> facets, String slug) {
        return facets.stream().filter(facet -> facet.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new AssertionError("No facet for slug '" + slug + "' in " + facets))
                .jobCount();
    }
}
