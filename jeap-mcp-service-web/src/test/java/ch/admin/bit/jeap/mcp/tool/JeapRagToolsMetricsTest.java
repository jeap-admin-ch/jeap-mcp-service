package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.config.McpMetricsConfig;
import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("SameParameterValue")
@SpringJUnitConfig({McpMetricsConfig.class, JeapRagToolsMetricsTest.TestConfig.class})
@TestPropertySource(properties = {
        "jeap.mcp.upstream.retry.max-attempts=5",
        "jeap.mcp.upstream.retry.backoff-millis=0"
})
class JeapRagToolsMetricsTest {

    private static final String CLIENT_NAME = "spring-ai-mcp-client";

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private McpSyncClient projectRagClient;

    @Autowired
    private JeapRagTools tools;

    @BeforeEach
    void resetMock() {
        // The Spring context (and thus the @Bean mock) is cached across test methods; reset so each
        // test's callTool invocation count is isolated.
        reset(projectRagClient);
        when(projectRagClient.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - project-rag", "1.0.0").build());
    }

    @Test
    void timedMetricCountsOneToolInvocationAcrossRetryAttempts() {
        when(projectRagClient.callTool(any()))
                .thenThrow(new RuntimeException("Failed to enqueue message"))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(McpSchema.TextContent.builder("recovered").build()), false, null, null));

        String result = tools.getStatistics();

        assertEquals("recovered", result);
        verify(projectRagClient, times(2)).callTool(any());
        assertEquals(1, toolCallCount(JeapRagTools.TOOL_GET_STATISTICS));
        assertTrue(toolCallTotalTimeNanos(JeapRagTools.TOOL_GET_STATISTICS) > 0);
    }

    @Test
    void recordsSourceChunksByExtensionAndArea() {
        // A docs-area chunk is only ever returned by the documentation tool: docs/** is indexed solely in
        // the artificial jeap-docs project, so the code RAG tools return only code-area chunks. Drive each
        // chunk through the tool that can actually produce it so the test data stays production-faithful.
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(
                        "{\"results\":[{\"file_path\":\"jeap/docs/overview/x.md\",\"score\":0.8}]}").build()),
                false, null, null));
        tools.findInDocumentation("q", null);

        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(
                        "{\"results\":[{\"file_path\":\"jeap-messaging/src/main/Foo.java\",\"score\":0.7}]}").build()),
                false, null, null));
        tools.findCodeExamples("q", null, null, null, null, null);

        assertEquals(1.0, sourceChunkCount("md", "docs", JeapRagTools.TOOL_FIND_IN_DOCUMENTATION));
        assertEquals(1.0, sourceChunkCount("java", "code", JeapRagTools.TOOL_FIND_CODE_EXAMPLES));
    }

    @Test
    void recordsSourceChunksWhenIsErrorAbsent() {
        // isError is optional per the MCP spec (absent ⇒ success); a successful upstream response that
        // omits it (null) must still feed the chunk metric, not silently drop it. Uses a distinct
        // extension (adoc) so its counter does not collide with the other chunk tests sharing the
        // cached SimpleMeterRegistry.
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(
                        "{\"results\":[{\"file_path\":\"jeap/docs/overview/x.adoc\",\"score\":0.8}]}").build()),
                null, null, null));

        tools.findInDocumentation("q", null);

        assertEquals(1.0, sourceChunkCount("adoc", "docs", JeapRagTools.TOOL_FIND_IN_DOCUMENTATION));
    }

    @Test
    void parseFailureOnMalformedJsonIncrementsCounterButToolStillSucceeds() {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("{not valid json").build()), false, null, null));

        String result = tools.findCodeExamples("q", null, null, null, null, null);

        assertEquals("{not valid json", result, "the tool call still returns the upstream content");
        assertEquals(1.0, parseFailureCount(JeapRagTools.TOOL_FIND_CODE_EXAMPLES));
    }

    private double sourceChunkCount(String extension, String area, String tool) {
        Counter c = meterRegistry.find(McpMetrics.UPSTREAM_CHUNKS_FETCHED_COUNTER)
                .tag(McpMetrics.EXTENSION_TAG, extension)
                .tag(McpMetrics.AREA_TAG, area)
                .tag(McpMetrics.TOOL_TAG, tool)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    private double parseFailureCount(String tool) {
        Counter c = meterRegistry.find(McpMetrics.PARSE_FAILURES_COUNTER)
                .tag(McpMetrics.TOOL_TAG, tool)
                .counter();
        return c == null ? 0.0 : c.count();
    }

    private long toolCallCount(String toolName) {
        return meterRegistry.getMeters().stream()
                .filter(Timer.class::isInstance)
                .map(Timer.class::cast)
                .filter(timer -> McpMetrics.TIMER_NAME.equals(timer.getId().getName()))
                .filter(timer -> toolName.equals(timer.getId().getTag(McpMetrics.TOOL_TAG)))
                .mapToLong(Timer::count)
                .sum();
    }

    private double toolCallTotalTimeNanos(String toolName) {
        return meterRegistry.getMeters().stream()
                .filter(Timer.class::isInstance)
                .map(Timer.class::cast)
                .filter(timer -> McpMetrics.TIMER_NAME.equals(timer.getId().getName()))
                .filter(timer -> toolName.equals(timer.getId().getTag(McpMetrics.TOOL_TAG)))
                .mapToDouble(timer -> timer.totalTime(TimeUnit.NANOSECONDS))
                .sum();
    }

    @Configuration
    @EnableRetry
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        // McpMetrics is supplied by the imported McpMetricsConfig (consuming the MeterRegistry above),
        // so metric wiring lives in exactly one place — no local override here.

        @Bean
        McpSyncClient projectRagClient() {
            McpSyncClient client = mock(McpSyncClient.class);
            when(client.getClientInfo())
                    .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - project-rag", "1.0.0").build());
            return client;
        }

        @Bean
        JeapRagTools jeapRagTools(McpSyncClient projectRagClient, McpMetrics mcpMetrics) {
            JeapDocsProperties props = TestDocsProperties.defaults();
            DocumentSearch documentSearch = new DocumentSearch(new DocsReader(new DocPathPolicy(props)), props);
            return new JeapRagTools(List.of(projectRagClient), CLIENT_NAME, false, mcpMetrics, documentSearch);
        }
    }
}
