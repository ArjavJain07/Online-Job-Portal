package com.jobportal.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Unit tests for BotDetector (dated view analytics feature, decision 2: "write
// amplification and bot noise"): the denylist heuristic used to keep obvious scanner/script
// traffic out of the JobView table without touching Job.viewCount.
class BotDetectorTest {

    @Test
    void missingOrBlankUserAgentIsTreatedAsNonHuman() {
        assertThat(BotDetector.isLikelyNonHuman(null)).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("   ")).isTrue();
    }

    @Test
    void ordinaryDesktopAndMobileBrowsersAreHuman() {
        assertThat(BotDetector.isLikelyNonHuman(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/128.0.0.0 Safari/537.36")).isFalse();
        assertThat(BotDetector.isLikelyNonHuman(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Version/17.4 Safari/605.1.15")).isFalse();
        assertThat(BotDetector.isLikelyNonHuman(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Version/17.4 Mobile/15E148 Safari/604.1")).isFalse();
        assertThat(BotDetector.isLikelyNonHuman(
                "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0")).isFalse();
    }

    @Test
    void wellKnownCrawlersAreNonHuman() {
        assertThat(BotDetector.isLikelyNonHuman("Mozilla/5.0 (compatible; Googlebot/2.1; "
                + "+http://www.google.com/bot.html)")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Mozilla/5.0 (compatible; bingbot/2.0; "
                + "+http://www.bing.com/bingbot.htm)")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Mozilla/5.0 (compatible; AhrefsBot/7.0; "
                + "+http://ahrefs.com/robot/)")).isTrue();
    }

    @Test
    void scriptingAndHttpClientDefaultUserAgentsAreNonHuman() {
        assertThat(BotDetector.isLikelyNonHuman("curl/8.4.0")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Wget/1.21.3")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("python-requests/2.31.0")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Scrapy/2.11 (+https://scrapy.org)")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Java/17.0.9")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("okhttp/4.12.0")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("PostmanRuntime/7.36.0")).isTrue();
    }

    @Test
    void scannersAndPentestToolsAreNonHuman() {
        assertThat(BotDetector.isLikelyNonHuman("Mozilla/5.0 (Nikto)")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("sqlmap/1.7.11#stable")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("Nuclei - Open-source project (github.com/projectdiscovery/nuclei)"))
                .isTrue();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(BotDetector.isLikelyNonHuman("MOZILLA/5.0 (COMPATIBLE; GOOGLEBOT/2.1)")).isTrue();
        assertThat(BotDetector.isLikelyNonHuman("CURL/8.4.0")).isTrue();
    }
}
