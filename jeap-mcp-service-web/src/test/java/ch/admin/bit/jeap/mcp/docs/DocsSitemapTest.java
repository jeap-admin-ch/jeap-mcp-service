package ch.admin.bit.jeap.mcp.docs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocsSitemapTest {

    @Test
    void rendersOnePercentEncodedUriPerPath() {
        String sitemap = DocsSitemap.render(List.of("jeap-messaging-outbox/docs/getting-started.md"));

        assertTrue(sitemap.contains("jeap-docs://jeap-messaging-outbox%2Fdocs%2Fgetting-started.md"),
                "path is percent-encoded, ready to use as-is");
    }

    @Test
    void groupsByRepoUnderARepoHeading() {
        String sitemap = DocsSitemap.render(List.of(
                "jeap-crypto/docs/getting-started.md",
                "jeap-messaging/docs/getting-started.md",
                "jeap-messaging/README.md"));

        int cryptoHeading = sitemap.indexOf("### jeap-crypto");
        int messagingHeading = sitemap.indexOf("### jeap-messaging");
        assertTrue(cryptoHeading >= 0 && messagingHeading >= 0);
        // jeap-messaging's two paths both appear after its own heading, before the next repo's
        // (there is none after it here, so just check both are present at all under it)
        assertTrue(sitemap.substring(messagingHeading).contains("jeap-messaging%2Fdocs%2Fgetting-started.md"));
        assertTrue(sitemap.substring(messagingHeading).contains("jeap-messaging%2FREADME.md"));
    }

    @Test
    void encodesSpaceAsPercentTwentyNotPlus() {
        // URLEncoder alone (application/x-www-form-urlencoded) would produce '+' here, which is wrong
        // for a URI meant to be copied verbatim by an external client - RFC 3986 requires %20.
        String sitemap = DocsSitemap.render(List.of("jeap-crypto/docs/getting started.md"));

        assertTrue(sitemap.contains("jeap-docs://jeap-crypto%2Fdocs%2Fgetting%20started.md"));
        assertFalse(sitemap.contains("+"));
    }

    @Test
    void emptyInputRendersHeadingOnlyWithoutError() {
        String sitemap = DocsSitemap.render(List.of());

        assertFalse(sitemap.contains("###"));
        assertTrue(sitemap.contains("Documentation files"));
    }
}
