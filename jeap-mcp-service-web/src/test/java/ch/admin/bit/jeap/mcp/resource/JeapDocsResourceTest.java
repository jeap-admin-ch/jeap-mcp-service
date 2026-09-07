package ch.admin.bit.jeap.mcp.resource;

import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import ch.admin.bit.jeap.mcp.tool.JeapOverviewTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JeapDocsResourceTest {

    @TempDir
    Path root;

    private SimpleMeterRegistry registry;
    private JeapDocsResource resource;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("jeap-messaging/docs"));
        Files.writeString(root.resolve("jeap-messaging/docs/outbox.md"), "# Outbox\nthe full body");
        Files.createDirectories(root.resolve("jeap-messaging-outbox/docs"));
        Files.writeString(root.resolve("jeap-messaging-outbox/docs/getting-started.md"), "# Getting started");
        registry = new SimpleMeterRegistry();
        resource = new JeapDocsResource(
                new DocsReader(new DocPathPolicy(TestDocsProperties.withDocsRoot(root.toString()))),
                new JeapOverviewTool(new ClassPathResource("jeap-docs")),
                new McpMetrics(registry));
    }

    @Test
    void indexResourceReturnsCuratedOverviewAndCountsAsMdDocs() {
        String content = resource.index();

        assertTrue(content.contains("jeap-messaging"), "index resource returns the curated repo overview");
        assertEquals(1.0, readCount(JeapDocsResource.INDEX_RESOURCE_NAME, "md", "docs"));
    }

    @Test
    void indexResourceAppendsSitemapOfReadyToUseRefUris() {
        String content = resource.index();

        assertTrue(content.contains("jeap-docs://jeap-messaging-outbox%2Fdocs%2Fgetting-started.md"),
                "sitemap gives a ready-to-use, percent-encoded URI for each known doc");
    }

    @Test
    void getDocumentDecodesPercentEncodedSlashesAndReturnsFullBody() {
        String encodedRef = URLEncoder.encode("jeap-messaging/docs/outbox.md", StandardCharsets.UTF_8);

        String body = resource.getDocument(encodedRef);

        assertTrue(body.contains("the full body"));
        assertEquals(1.0, readCount(JeapDocsResource.DOC_RESOURCE_NAME, "md", "docs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jeap-messaging/docs/outbox.md#configuration",
            "jeap-messaging/docs/outbox.md?plain=1"
    })
    void getDocumentNormalizesFragmentAndQueryAfterDecoding(String rawRef) {
        String encodedRef = URLEncoder.encode(rawRef, StandardCharsets.UTF_8);

        String body = resource.getDocument(encodedRef);

        assertTrue(body.contains("the full body"), "the real file is read despite #anchor / ?query");
        assertEquals(1.0, readCount(JeapDocsResource.DOC_RESOURCE_NAME, "md", "docs"));
    }

    @Test
    void getDocumentThrowsCleanMcpErrorAndDoesNotCountWhenNotFound() {
        String encodedRef = URLEncoder.encode("jeap-messaging/docs/missing.md", StandardCharsets.UTF_8);

        McpError error = assertThrows(McpError.class, () -> resource.getDocument(encodedRef));

        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().contains(root.toString()), "must not leak filesystem paths");
        assertEquals(0.0, totalReadCount());
    }

    @Test
    void getDocumentThrowsCleanMcpErrorAndDoesNotCountWhenDenied() {
        String encodedRef = URLEncoder.encode("jeap-messaging/docs/../../etc/passwd", StandardCharsets.UTF_8);

        assertThrows(McpError.class, () -> resource.getDocument(encodedRef));
        assertEquals(0.0, totalReadCount());
    }

    @Test
    void completeRefSuggestsMatchingPathsByPrefix() {
        List<String> suggestions = resource.completeRef("jeap-messaging-outbox");

        assertEquals(List.of("jeap-messaging-outbox/docs/getting-started.md"), suggestions);
    }

    @Test
    void completeRefWithEmptyValueReturnsAllKnownPaths() {
        List<String> suggestions = resource.completeRef("");

        assertTrue(suggestions.contains("jeap-messaging/docs/outbox.md"));
        assertTrue(suggestions.contains("jeap-messaging-outbox/docs/getting-started.md"));
    }

    @Test
    void completeRefWithNoMatchReturnsEmptyList() {
        assertTrue(resource.completeRef("does-not-exist").isEmpty());
    }

    @Test
    void completeRefCountsAsCompletionCallTaggedByResourceAndMatched() {
        resource.completeRef("jeap-messaging");

        Counter c = registry.find(McpMetrics.RESOURCE_COMPLETION_CALLS_COUNTER)
                .tag(McpMetrics.RESOURCE_TAG, JeapDocsResource.DOC_RESOURCE_NAME)
                .tag(McpMetrics.MATCHED_TAG, "true")
                .counter();
        assertNotNull(c);
        assertEquals(1.0, c.count());
    }

    @Test
    void completeRefWithNoMatchCountsAsUnmatchedCompletionCall() {
        resource.completeRef("does-not-exist");

        Counter c = registry.find(McpMetrics.RESOURCE_COMPLETION_CALLS_COUNTER)
                .tag(McpMetrics.RESOURCE_TAG, JeapDocsResource.DOC_RESOURCE_NAME)
                .tag(McpMetrics.MATCHED_TAG, "false")
                .counter();
        assertNotNull(c);
        assertEquals(1.0, c.count());
    }

    private double readCount(String resourceName, String extension, String area) {
        Counter c = registry.find(McpMetrics.RESOURCE_READS_COUNTER)
                .tag(McpMetrics.RESOURCE_TAG, resourceName)
                .tag(McpMetrics.EXTENSION_TAG, extension)
                .tag(McpMetrics.AREA_TAG, area)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    private double totalReadCount() {
        return registry.find(McpMetrics.RESOURCE_READS_COUNTER).counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
