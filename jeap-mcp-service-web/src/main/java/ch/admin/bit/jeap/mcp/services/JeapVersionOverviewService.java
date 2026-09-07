package ch.admin.bit.jeap.mcp.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class JeapVersionOverviewService {

    // Public default: the jEAP umbrella repo on GitHub. Some deployment environments can't reach
    // GitHub at all (e.g. restricted outbound internet access) - jeap.mcp.version-overview.source-url
    // lets an instance point this at an internally-reachable mirror instead, without needing this
    // (public, OSS-bound) class to know that mirror's address itself.
    private static final String DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/jeap-admin-ch/jeap/main/docs/jeap-version-overview.md";

    private final RestClient restClient;
    private final String sourceUrl;
    private final AtomicReference<String> cachedContent = new AtomicReference<>("");

    public JeapVersionOverviewService(RestClient restClient,
            @Value("${jeap.mcp.version-overview.source-url:" + DEFAULT_SOURCE_URL + "}") String sourceUrl) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
        // No eager call here: this constructor runs on Spring's main startup thread, so a
        // synchronous fetch here would block application startup itself on an external network
        // call (with a slow/unreachable path, previously for minutes - see the connect/read
        // timeout now set on the RestClient bean in McpToolConfig). @Scheduled below already
        // does the initial fetch too, 1 second after startup, but on a background scheduler
        // thread that can't block bean creation.
    }

    /**
     * Periodically refresh the version overview from its source (see jeap.mcp.version-overview.source-url).
     * Also does the initial load, 1 second after startup - on a background scheduler thread, not
     * the main startup thread.
     * Interval is configurable via jeap.mcp.version-overview.refresh-interval-millis property.
     * Default: 1 hour (3600000 milliseconds).
     */
    @Scheduled(fixedDelayString = "${jeap.mcp.version-overview.refresh-interval-millis:3600000}", initialDelay = 1000)
    public void refreshVersionOverview() {
        try {
            log.debug("Fetching jEAP version overview from {}...", sourceUrl);
            String content = restClient.get().uri(URI.create(sourceUrl)).retrieve().body(String.class);
            if (content != null && !content.isBlank()) {
                cachedContent.set(content);
                log.info("Successfully updated jEAP version overview cache");
            } else {
                log.warn("Received empty content from {} for version overview", sourceUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch jEAP version overview from {}. Using cached content if available.", sourceUrl, e);
        }
    }

    /**
     * Get the cached version overview content.
     * If content is not available, returns a placeholder message.
     */
    public String getVersionOverview() {
        String content = cachedContent.get();
        if (content.isBlank()) {
            return "Version overview is currently being loaded. Please try again shortly.";
        }
        return content;
    }

    /**
     * Get filtered version overview content.
     *
     * @param filter Optional filter: 'parent', 'libraries', 'products', 'spring', 'thirdparty', or null for all
     * @return Filtered version content or full content if filter is invalid/null
     */
    public String getVersionOverviewFiltered(String filter) {
        String content = getVersionOverview();

        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("all")) {
            return content;
        }

        return switch (filter.toLowerCase()) {
            case "parent" -> extractSection(content, "# jEAP Parent", "# jEAP Library");
            case "libraries" -> extractSection(content, "# jEAP Library Versions", "# Spring Versions");
            case "products" -> extractSection(content, "# jEAP Products", "# Managed 3rd Party");
            case "spring" -> extractSection(content, "# Spring Versions", "# jEAP Products");
            case "thirdparty" -> extractSection(content, "# Managed 3rd Party", null);
            default -> content; // Return full content for unknown filters
        };
    }

    private String extractSection(String content, String startMarker, String endMarker) {
        int startIndex = content.indexOf(startMarker);
        if (startIndex == -1) {
            return "No content found for filter.";
        }

        if (endMarker == null) {
            return content.substring(startIndex);
        }

        int endIndex = content.indexOf(endMarker, startIndex);
        if (endIndex == -1) {
            return content.substring(startIndex);
        }

        return content.substring(startIndex, endIndex).trim();
    }
}
