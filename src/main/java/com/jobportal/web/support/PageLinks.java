package com.jobportal.web.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
