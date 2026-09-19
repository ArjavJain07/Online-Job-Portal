package com.jobportal.web.support;

import com.jobportal.dto.SkillFacet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

// Template helper "@pageLinks" (Section 7.9): builds every link fragments/pagination
// needs for the current request's Page<T>, keeping every other query parameter (filters,
// sort, ...) intact.
//
// Everything is resolved into plain strings and numbers by build(page), called once from
// the template with th:with, instead of computing each href with its own bean call and
// some arithmetic inside th:each: Thymeleaf 3.1 evaluates a bean method call combined
// with an arithmetic argument (for example page.number + 1) with
// "Instantiation of new objects and access to static classes or parameters is forbidden
// in this context" in some attribute positions. Building the whole result here, with
// plain Java, sidesteps that restriction and keeps the template to simple property access.
@Component("pageLinks")
public class PageLinks {

    public String page(int n) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replaceQueryParam("page", n)
                .build()
                .toUriString();
    }

    // The skill facet chips beside the job search results (Section 10.8). Each one is a
    // link, not a form control: selecting a facet is navigation, so the result is a real
    // URL that can be bookmarked and shared, the Back button undoes it, and every other
    // filter in the query string survives untouched - exactly what fragments/job-filters
    // already promises for the form itself.
    //
    // Built here in one call, like build(Page) above and for the same reason: Thymeleaf
    // 3.1's restricted mode refuses some bean-call-with-argument forms inside attributes,
    // so the template does one th:with and then only reads properties.
    //
    // "page" is reset because a facet changes the result set - staying on page 4 of a
    // search that now has one page would show an empty list (the same reason the filter
    // form has no page field).
    public List<SkillFacetLink> skillLinks(List<SkillFacet> facets) {
        List<SkillFacetLink> links = new ArrayList<>();
        for (SkillFacet facet : facets) {
            // Selecting the chip that is already selected clears the filter, so one chip
            // is both "filter by this" and "stop filtering by this".
            String href = facet.selected() ? withSkill(null) : withSkill(facet.slug());
            links.add(new SkillFacetLink(facet.label(), facet.jobCount(), facet.selected(), href));
        }
        return links;
    }

    // The "Any skill" link: this search with no skill facet applied.
    public String anySkillHref() {
        return withSkill(null);
    }

    private String withSkill(String slug) {
        UriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequest()
                .replaceQueryParam("page", 0);
        // replaceQueryParam with no values removes the parameter entirely, which is what
        // "no skill filter" has to mean - leaving "skill=" behind would round-trip as a
        // blank filter and clutter every shared link.
        return (slug == null ? builder.replaceQueryParam("skill") : builder.replaceQueryParam("skill", slug))
                .build()
                .toUriString();
    }

    // One skill facet ready to render: "Java", 24, whether it is the current filter, and
    // where clicking it goes.
    public record SkillFacetLink(String label, long jobCount, boolean selected, String href) {
    }

    public PaginationLinks build(Page<?> page) {
        boolean hasPrevious = !page.isFirst();
        boolean hasNext = !page.isLast();
        String previousHref = hasPrevious ? page(page.getNumber() - 1) : "#";
        String nextHref = hasNext ? page(page.getNumber() + 1) : "#";
        return new PaginationLinks(previousHref, hasPrevious, nextHref, hasNext, windowLinks(page),
                rangeSummary(page));
    }

    // Up to 5 page-number links centred on the current page.
    private List<PageLink> windowLinks(Page<?> page) {
        int from = Math.max(0, page.getNumber() - 2);
        int to = Math.min(page.getTotalPages() - 1, page.getNumber() + 2);
        List<PageLink> links = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            links.add(new PageLink(i, page(i), i == page.getNumber()));
        }
        return links;
    }

    // "Showing 11-20 of 57" (or "Showing 1-1 of 1" on the last, partial page).
    private String rangeSummary(Page<?> page) {
        long from = (long) page.getNumber() * page.getSize() + 1;
        long to = Math.min((long) (page.getNumber() + 1) * page.getSize(), page.getTotalElements());
        return "Showing " + from + "-" + to + " of " + page.getTotalElements();
    }

    // Everything fragments/pagination needs for one render: the Previous/Next hrefs (and
    // whether they are enabled), the page-number links, and the summary line.
    public record PaginationLinks(String previousHref, boolean hasPrevious, String nextHref, boolean hasNext,
            List<PageLink> pageLinks, String rangeSummary) {
    }

    // One page-number link: the page it points to (0-based, so the template shows
    // number + 1), its href, and whether it is the current page.
    public record PageLink(int number, String href, boolean active) {
    }
}
