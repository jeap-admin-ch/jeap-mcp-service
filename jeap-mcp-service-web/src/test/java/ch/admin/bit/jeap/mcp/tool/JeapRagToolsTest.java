package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPathPolicy;
import ch.admin.bit.jeap.mcp.docs.DocsReader;
import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.docs.TestDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("SameParameterValue")
class JeapRagToolsTest {

    private static final String CLIENT_NAME = "spring-ai-mcp-client";
    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    @TempDir
    Path docsRoot;

    private McpSyncClient projectRagClient;
    private DocumentSearch documentSearch;
    private JeapRagTools tools;

    @BeforeEach
    void setUp() {
        projectRagClient = mock(McpSyncClient.class);
        when(projectRagClient.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - project-rag", "1.0.0").build());
        stubSuccess("anything");
        JeapDocsProperties docsProperties = TestDocsProperties.withDocsRoot(docsRoot.toString());
        documentSearch = new DocumentSearch(new DocsReader(new DocPathPolicy(docsProperties)), docsProperties);
        tools = newTools(List.of(projectRagClient), CLIENT_NAME, false);
    }

    private JeapRagTools newTools(List<McpSyncClient> clients, String name, boolean enabled) {
        return new JeapRagTools(clients, name, enabled, new McpMetrics(new SimpleMeterRegistry()), documentSearch);
    }

    @Test
    void findCodeExamplesPassesAllNonNullArgsAsQueryCodebase() {
        tools.findCodeExamples("jeap-messaging", "path/to", "jeap-messaging", 5, 0.6, false);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("query_codebase", request.name());
        assertEquals(Map.of(
                "query", "jeap-messaging",
                "path", "path/to",
                "project", "jeap-messaging",
                "limit", 5,
                "min_score", 0.6,
                "hybrid", false
        ), request.arguments());
    }

    @Test
    void findCodeExamplesSkipsNullOptionalArguments() {
        tools.findCodeExamples("kafka listener", null, null, null, null, null);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("query_codebase", request.name());
        assertEquals(Map.of("query", "kafka listener"), request.arguments());
    }

    @Test
    void findDefinitionPassesAllRequestedArguments() {
        tools.findDefinition("Foo.java", 42, 0, "jeap-messaging");

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("find_definition", request.name());
        assertEquals(Map.of(
                "file_path", "Foo.java",
                "line", 42,
                "column", 0,
                "project", "jeap-messaging"
        ), request.arguments());
    }

    @Test
    void findReferencesOmitsNullOptionals() {
        tools.findReferences("Foo.java", 10, 4, null, null);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("find_references", request.name());
        assertEquals(Map.of("file_path", "Foo.java", "line", 10, "column", 4), request.arguments());
    }

    @Test
    void getCallGraphIncludesDepthWhenProvided() {
        tools.getCallGraph("Foo.java", 1, 0, 3, null);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("get_call_graph", request.name());
        assertEquals(Map.of("file_path", "Foo.java", "line", 1, "column", 0, "depth", 3), request.arguments());
    }

    @Test
    void searchByFiltersPassesListArguments() {
        tools.searchByFilters("kafka", List.of("java"), List.of("java"), List.of("**/messaging/**"), 20, null);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("search_by_filters", request.name());
        assertEquals(Map.of(
                "query", "kafka",
                "file_extensions", List.of("java"),
                "languages", List.of("java"),
                "path_patterns", List.of("**/messaging/**"),
                "limit", 20
        ), request.arguments());
    }

    @Test
    void getStatisticsSendsEmptyArgumentsMap() {
        tools.getStatistics();

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("get_statistics", request.name());
        assertEquals(Map.of(), request.arguments());
    }

    @Test
    void findInDocumentationIssuesQueryCodebaseScopedToDocsProject() {
        stubResults("{\"results\":[{\"file_path\":\"jeap-messaging/docs/outbox.md\",\"score\":0.9}]}");

        tools.findInDocumentation("how does the outbox work", null);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals("query_codebase", request.name());
        Map<String, Object> args = request.arguments();
        assertEquals("jeap-docs", args.get("project"), "always scoped to the jeap-docs project");
        assertEquals(0.5, ((Number) args.get("min_score")).doubleValue(), 1e-9);
        assertEquals(15, ((Number) args.get("limit")).intValue(), "maxDocs(5) * OVER_FETCH(3)");
        assertEquals(Boolean.TRUE, args.get("hybrid"));
        assertFalse(args.containsKey("path_patterns"), "docs search never uses path_patterns");
    }

    @Test
    void findInDocumentationClampsRequestedLimitOnUpstreamAndOutput() {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"file_path\":\"jeap-messaging/docs/d").append(i).append(".md\",\"score\":0.")
                    .append(9 - i).append("}");
        }
        sb.append("]}");
        stubResults(sb.toString());

        String json = tools.findInDocumentation("q", 1000);

        McpSchema.CallToolRequest request = captureRequest();
        assertEquals(15, ((Number) request.arguments().get("limit")).intValue(),
                "limit=1000 clamps to maxDocs(5) * OVER_FETCH(3) on the upstream limit");
        JsonNode out = MAPPER.readTree(json);
        assertEquals(5, out.size(), "and the reshaped output is clamped to maxDocs(5)");
        assertTrue(out.get(0).has("path") && out.get(0).has("score"));
    }

    @Test
    void singleTextContentIsReturnedAsPlainText() {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("plain-text result").build()), false, null, null));

        String result = tools.getStatistics();

        assertEquals("plain-text result", result);
    }

    @Test
    void multipleTextContentsAreConcatenated() {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("part-1 ").build(), McpSchema.TextContent.builder("part-2").build()),
                false, null, null));

        String result = tools.getStatistics();

        assertEquals("part-1 part-2", result);
    }

    @Test
    void upstreamErrorResultIsReturnedToCallerAsContent() {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("boom").build()), true, null, null));

        // isError=true is preserved at the upstream-protocol level; the wrapper hands the content
        // through so the agent can read the structured error rather than getting an RPC exception.
        String result = tools.getStatistics();

        assertEquals("boom", result);
    }

    @Test
    void missingProjectRagClientWithMcpDisabledLogsWarningAndFailsOnlyAtCallTime() {
        JeapRagTools toolsWithoutClient = newTools(List.of(), CLIENT_NAME, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, toolsWithoutClient::getStatistics);
        assertTrue(ex.getMessage().contains("no 'project-rag' MCP client"), ex.getMessage());
    }

    @Test
    void missingProjectRagClientWithMcpEnabledFailsFastAtStartup() {
        List<McpSyncClient> mcpSyncClients = List.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newTools(mcpSyncClients, CLIENT_NAME, true));

        assertTrue(ex.getMessage().contains("spring.ai.mcp.client.enabled=true"), ex.getMessage());
        assertTrue(ex.getMessage().contains("project-rag"), ex.getMessage());
    }

    @Test
    void ignoresClientsOnOtherConnections() {
        McpSyncClient other = mock(McpSyncClient.class);
        when(other.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - some-other", "1.0.0").build());
        JeapRagTools picky = newTools(List.of(other), CLIENT_NAME, false);

        assertThrows(IllegalStateException.class, picky::getStatistics);
    }

    @Test
    void picksProjectRagClientWhenMultipleAreConnected() {
        McpSyncClient other = mock(McpSyncClient.class);
        when(other.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder(CLIENT_NAME + " - some-other", "1.0.0").build());

        JeapRagTools toolsWithBoth = newTools(List.of(other, projectRagClient), CLIENT_NAME, false);
        toolsWithBoth.getStatistics();

        verify(projectRagClient).callTool(any());
        verify(other, never()).callTool(any());
    }

    @Test
    void respectsCustomMcpClientName() {
        McpSyncClient renamedClient = mock(McpSyncClient.class);
        when(renamedClient.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder("custom-mcp - project-rag", "1.0.0").build());
        when(renamedClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("ok").build()), false, null, null));

        JeapRagTools renamed = new JeapRagTools(List.of(renamedClient), "custom-mcp", false,
                new McpMetrics(new SimpleMeterRegistry()), documentSearch);
        assertEquals("ok", renamed.getStatistics());
    }

    private void stubSuccess(String content) {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(content).build()), false, null, null));
    }

    /** Stub the upstream to return a QueryResponse JSON (so findInDocumentation's reshape can run). */
    private void stubResults(String queryResponseJson) {
        when(projectRagClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder(queryResponseJson).build()), false, null, null));
    }

    private McpSchema.CallToolRequest captureRequest() {
        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(projectRagClient).callTool(captor.capture());
        return captor.getValue();
    }
}
