package com.jobportal.web.support;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

// Open-redirect protection for the "go back to where you were" redirect after a simple
// POST button refuses an action (Section 4.9, 7.3): only the path (and query) of a
// same-host Referer is trusted, never the header value itself, and anything else falls
// back to the dashboard.
public final class SafeRedirects {

    private SafeRedirects() {
    }

    public static String backOrDashboard(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                URI refererUri = URI.create(referer);
                String host = refererUri.getHost();
                String path = refererUri.getRawPath();
                if (host != null && host.equalsIgnoreCase(request.getServerName()) && path != null
                        && !path.isBlank()) {
                    String query = refererUri.getRawQuery();
                    return query == null ? path : path + "?" + query;
                }
            } catch (IllegalArgumentException e) {
                // malformed Referer header: fall through to the default below
            }
        }
        return "/dashboard";
    }
}
