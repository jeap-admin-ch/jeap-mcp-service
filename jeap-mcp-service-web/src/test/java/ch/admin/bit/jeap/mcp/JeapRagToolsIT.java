package ch.admin.bit.jeap.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the fixed jEAP RAG tool surface end-to-end against a live
 * upstream {@code project-rag} stdio MCP server (the bundled binary configured in
 * {@code application-mcp-client-test.yml}). Disabled by default - run with:
 * <pre>
 * MCP_CLIENT_TEST=true ./mvnw verify -Dit.test=JeapRagToolsIT
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mcp-client-test")
@EnabledIfEnvironmentVariable(named = "MCP_CLIENT_TEST", matches = "true")
class JeapRagToolsIT {

    private static final Set<String> EXPECTED_TOOL_NAMES = Set.of(
            "jeap_overview",
            "jeap_version_overview",
            "jeap_find_code_examples",
            "jeap_find_definition",
            "jeap_find_references",
            "jeap_get_call_graph",
            "jeap_search_by_filters",
            "jeap_get_statistics",
            "jeap_find_in_documentation",
            "jeap_get_document");

    @LocalServerPort
    private int port;

    @Test
    void shouldExposeOnlyTheFixedJeapToolSurface() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);

        JsonNode parsed = McpClientHarness.parseMcpBody(body);
        List<String> names = McpClientHarness.toolNames(parsed);

        assertEquals(EXPECTED_TOOL_NAMES.size(), names.size(),
                "Expected exactly " + EXPECTED_TOOL_NAMES.size() + " tools but got " + names);
        assertEquals(EXPECTED_TOOL_NAMES, Set.copyOf(names),
                "Tool surface does not match the fixed jEAP allowlist; got " + names);
        assertTrue(names.stream().allMatch(n -> n.startsWith("jeap_")),
                "All tools must be jeap_*-prefixed, got " + names);
    }

    @Test
    void shouldCallJeapGetStatisticsThroughUpstream() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"jeap_get_statistics","arguments":{}}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("\"isError\":true"),
                "Tool call should succeed, got: " + response.getBody());
    }

    @Test
    void shouldCallJeapFindCodeExamplesThroughUpstream() {
        McpClientHarness harness = new McpClientHarness(port);
        harness.initialize();

        ResponseEntity<String> response = harness.postJsonRpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"jeap_find_code_examples",
                           "arguments":{"query":"jeap-messaging"}}}
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("\"isError\":true"),
                "Tool call should succeed, got: " + response.getBody());
    }
}
