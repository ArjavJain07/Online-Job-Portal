package com.jobportal.web.support;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

// Template helper "@pageLinks" (Section 7.9): builds a link to another page of the
// current list while keeping every other query parameter (filters, sort, ...) intact.
@Component("pageLinks")
public class PageLinks {

    public String page(int n) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replaceQueryParam("page", n)
                .build()
                .toUriString();
    }
}
