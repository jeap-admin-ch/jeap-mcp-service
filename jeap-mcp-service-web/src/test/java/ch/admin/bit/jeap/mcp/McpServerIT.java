package ch.admin.bit.jeap.mcp;

import ch.admin.bit.jeap.mcp.docs.JeapDocsProperties;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import ch.admin.bit.jeap.mcp.resource.JeapDocsResource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SameParameterValue")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpServerIT {

    private static final String MCP_TOOL_CALLS_METRIC = "jeap.mcp.tool.calls";
    private static final String TOOL_TAG = "tool";
    private static final String JEAP_OVERVIEW_TOOL = "jeap_overview";

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JeapDocsProperties docsProperties;

    @Test
    void docsConfigurationPropertiesAreBound() {
        assertEquals("target/test-docs", docsProperties.docsRoot());
        assertEquals(7, docsProperties.maxDocs());
        assertEquals(0.42, docsProperties.minScore(), 1e-9);
        assertFalse(docsProperties.hybrid());
    }

    @Test
    void mcpEndpointShouldAcceptInitializeRequest() {
        McpClientHarness harness = new McpClientHarness(port);

        ResponseEntity<String> response = harness.initialize();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jeap-mcp-server"));
    }

    /**
     * Regression test for modelcontextprotocol/java-sdk#1072: with MCP Java SDK 2.0.0 an unknown
     * JSON-RPC method such as {@code server/discover} (the discovery probe introduced by MCP spec
     * revision 2026-07-28, which replaces the initialize handshake) was answered with HTTP 500
     * instead of a JSON-RPC "method not found" error, so those clients could not fall back to
     * the legacy initialize flow.
     */
    @Test
    void mcpEndpointShouldAnswerUnknownMethodWithJsonRpcErrorInsteadOfHttp500() {
        McpClientHarness harness = new McpClientHarness(port);

        ResponseEntity<String> response = harness.postJsonRpcWithoutStatusCheck("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}
                """);

        assertFalse(response.getStatusCode().is5xxServerError(),
                "Unknown MCP method must not cause a server error, got " + response.getStatusCode());
        assertNotNull(response.getBody());
        JsonNode error = McpClientHarness.parseMcpBody(response.getBody()).path("error");
        assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, error.path("code").asInt());
    }

    @Test
    void mcpEndpointShouldListTools() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jeap_overview"));
    }

    @Test
    void mcpEndpointShouldCallTool() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();
        long countBefore = toolCallCount(JEAP_OVERVIEW_TOOL);

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"jeap_overview","arguments":{}}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jeap-messaging"));
        assertEquals(countBefore + 1, toolCallCount(JEAP_OVERVIEW_TOOL));
        assertTrue(toolCallTotalTimeNanos(JEAP_OVERVIEW_TOOL) > 0);
    }

    @Test
    void mcpEndpointShouldListResourcesWithCuratedIndexOnly() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":4,"method":"resources/list","params":{}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Context-sparing: resources/list carries the single curated index resource, not one entry
        // per docs/** file (that discovery happens via the jeap-docs://{ref} resource template instead).
        assertTrue(response.getBody().contains("jeap-docs://index"));
    }

    @Test
    void mcpEndpointShouldListDocResourceTemplate() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":5,"method":"resources/templates/list","params":{}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jeap-docs://{ref}"));
    }

    @Test
    void mcpEndpointShouldReadIndexResource() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":6,"method":"resources/read",
                 "params":{"uri":"jeap-docs://index"}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jeap-messaging"));
        assertTrue(resourceReadCount(JeapDocsResource.INDEX_RESOURCE_NAME) > 0);
    }

    @Test
    void mcpEndpointShouldReadDocResourceTemplateWithPercentEncodedRef() throws IOException {
        Path docFile = Path.of("target/test-docs/jeap-messaging/docs/outbox.md");
        Files.createDirectories(docFile.getParent());
        Files.writeString(docFile, "# Outbox\nresource template full body");
        String encodedRef = URLEncoder.encode("jeap-messaging/docs/outbox.md", StandardCharsets.UTF_8);
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":7,"method":"resources/read",
                 "params":{"uri":"jeap-docs://%s"}}
                """.formatted(encodedRef));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("resource template full body"));
        assertTrue(resourceReadCount(JeapDocsResource.DOC_RESOURCE_NAME) > 0);
    }

    private long resourceReadCount(String resourceName) {
        Counter c = meterRegistry.find(McpMetrics.RESOURCE_READS_COUNTER)
                .tag(McpMetrics.RESOURCE_TAG, resourceName)
                .counter();
        return c == null ? 0 : (long) c.count();
    }

    private long toolCallCount(String toolName) {
        Timer timer = meterRegistry.find(MCP_TOOL_CALLS_METRIC).tag(TOOL_TAG, toolName).timer();
        return timer == null ? 0 : timer.count();
    }

    private double toolCallTotalTimeNanos(String toolName) {
        Timer timer = meterRegistry.find(MCP_TOOL_CALLS_METRIC).tag(TOOL_TAG, toolName).timer();
        return timer == null ? 0 : timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
