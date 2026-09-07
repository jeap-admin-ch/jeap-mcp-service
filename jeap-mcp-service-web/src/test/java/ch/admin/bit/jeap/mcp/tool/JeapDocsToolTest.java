package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JeapDocsToolTest {

    @TempDir
    Path root;

    private SimpleMeterRegistry registry;
    private JeapDocsTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("jeap-messaging/docs"));
        Files.writeString(root.resolve("jeap-messaging/docs/outbox.md"), "# Outbox\nthe full body");
        Files.writeString(root.resolve("jeap-messaging/README.md"), "# Readme body");
        registry = new SimpleMeterRegistry();
        tool = new JeapDocsTool(
                new DocsReader(new DocPathPolicy(TestDocsProperties.withDocsRoot(root.toString()))),
                new McpMetrics(registry));
    }

    @Test
    void returnsFullBodyForAllowedDoc() {
        String body = tool.getDocument("jeap-messaging/docs/outbox.md");
        assertTrue(body.contains("the full body"));
        assertEquals(1.0, fetchCount("md", "docs"));
    }

    @Test
    void repoRootReadmeCountsAsAreaCode() {
        String body = tool.getDocument("jeap-messaging/README.md");
        assertTrue(body.contains("Readme body"));
        assertEquals(1.0, fetchCount("md", "code"), "a repo-root README is area=code, derived not hardcoded");
    }

    // Fragment / query / combined: the file actually read is .../outbox.md and the metric tag is a clean
    // "md" (never "md#configuration" / "md?plain=1"). Anchor parsing itself is covered by DocRefTest.
    @ParameterizedTest
    @ValueSource(strings = {
            "jeap-messaging/docs/outbox.md#configuration",
            "jeap-messaging/docs/outbox.md?plain=1",
            "jeap-messaging/docs/outbox.md?plain=1#configuration"
    })
    void normalizesFragmentAndQueryForFetchAndMetric(String input) {
        String body = tool.getDocument(input);
        assertTrue(body.contains("the full body"), "the real file is read, no NoSuchFileException");
        assertEquals(1.0, fetchCount("md", "docs"), "extension tag is clean 'md', never with #/? suffix");
        assertEquals(0.0, fetchCount("md#configuration", "docs"));
        assertEquals(0.0, fetchCount("md?plain=1", "docs"));
    }

    @Test
    void notFoundReturnsCleanErrorAndDoesNotCount() {
        String result = tool.getDocument("jeap-messaging/docs/missing.md");
        assertFalse(result.contains(root.toString()), "must not leak filesystem paths");
        assertEquals(0.0, totalFetchCount(), "a failed read records no fetch metric");
    }

    @Test
    void deniedPathReturnsErrorAndDoesNotCount() {
        String result = tool.getDocument("jeap-messaging/docs/../../etc/passwd");
        assertNotNull(result);
        assertEquals(0.0, totalFetchCount());
    }

    private double fetchCount(String extension, String area) {
        Counter c = registry.find(McpMetrics.DOCS_FETCHED_COUNTER)
                .tag(McpMetrics.EXTENSION_TAG, extension)
                .tag(McpMetrics.AREA_TAG, area)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    private double totalFetchCount() {
        return registry.find(McpMetrics.DOCS_FETCHED_COUNTER).counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
