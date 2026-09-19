package com.jobportal.util;

import java.util.List;
import java.util.Locale;

// Filters obviously non-human traffic out of dated view analytics (JobSearchService
// #recordView, decision 2 of the feature: "write amplification and bot noise"). The
// deployed app already takes a steady stream of automated scanner traffic (the VPS gets
// constant /.env probes - see the deployment notes); nothing stops the same class of
// traffic from also hitting GET /jobs/{id}, and unlike a human browsing session, a script
// rarely keeps cookies, so JobSearchService's per-session view dedupe alone would not
// catch it - every request looks like a brand new session.
//
// This is deliberately a small denylist heuristic, not a general bot-detection system: the
// goal is only to stop the things that both bulk-probe every route on the internet AND
// announce themselves in their own User-Agent - well-known crawlers, the scripting/HTTP
// libraries whose default User-Agent nobody bothers to change, and common scanning tools -
// not to catch a scraper that deliberately impersonates a browser. Nothing at the HTTP
// layer can tell that case apart from a real visitor, so it is out of scope here; the
// per-session dedupe in recordView is what bounds the damage either way (at most one row
// per session, exactly as it already bounds viewCount).
public final class BotDetector {

    // Matched case-insensitively as a substring of the User-Agent header. No genuine
    // browser UA (Chrome, Firefox, Safari, Edge - all "Mozilla/5.0 ... AppleWebKit ...")
    // contains any of these, so this list costs no false positives against real visitors;
    // it only ever removes traffic that was never a candidate reading a job posting.
    private static final List<String> NON_HUMAN_MARKERS = List.of(
            // Search engine and SEO crawlers.
            "bot", "spider", "crawl", "slurp",
            // Scripting languages' and HTTP client libraries' default User-Agent strings.
            "curl", "wget", "python-requests", "python-urllib", "scrapy", "go-http-client",
            "java/", "libwww-perl", "okhttp", "httpclient", "axios", "node-fetch",
            // Headless browsers and browser-automation frameworks.
            "headlesschrome", "phantomjs", "puppeteer", "playwright", "selenium",
            // API tools that are never a candidate browsing the public site.
            "postman", "insomnia",
            // Vulnerability/port scanners - the same class of tool behind the /.env probes.
            "masscan", "nmap", "nikto", "sqlmap", "nuclei", "zgrab");

    private BotDetector() {
    }

    // True for a missing or blank User-Agent (every real browser sends one - its absence is
    // itself a signal, not a neutral default) or one containing any marker above. Called
    // once per candidate view, before anything is written, so a match costs nothing beyond
    // a handful of substring scans.
    public static boolean isLikelyNonHuman(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        for (String marker : NON_HUMAN_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
