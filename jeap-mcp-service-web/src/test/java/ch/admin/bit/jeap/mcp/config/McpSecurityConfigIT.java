package ch.admin.bit.jeap.mcp.config;

import ch.admin.bit.jeap.mcp.McpClientHarness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the dual security posture configured in {@link McpSecurityConfig}:
 * <ul>
 *     <li>{@code /mcp/**} is reachable anonymously — that's how MCP clients connect.</li>
 *     <li>Other paths are protected by the jEAP OAuth2 resource-server starter and reject
 *         unauthenticated requests (no implicit bypass via the high-precedence MCP chain).</li>
 *     <li>A {@code /mcp/**} request body over {@code jeap.mcp.max-request-body-bytes} is
 *         rejected by {@link McpRequestSizeFilter} before being parsed.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Small enough to keep the oversized-body test cheap, generous enough that a real initialize
// request (the other tests in this class send one) still comfortably fits.
@TestPropertySource(properties = "jeap.mcp.max-request-body-bytes=512")
class McpSecurityConfigIT {

    @LocalServerPort
    private int port;

    private final RestClient restClient = RestClient.create();

    @Test
    void mcpEndpointAllowsAnonymousAccess() {
        McpClientHarness harness = new McpClientHarness(port);

        ResponseEntity<String> response = harness.initialize();

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "MCP initialize must succeed without auth headers; got " + response.getStatusCode());
    }

    @Test
    void nonMcpEndpointsAreNotImplicitlyOpened() {
        // Hit an arbitrary non-MCP path. The dedicated MCP chain matches only /mcp/**, so this
        // request must be handled by the jEAP resource-server chain - which without a token
        // rejects rather than serving the request anonymously.
        HttpStatusCode status = restClient.get()
                .uri(new McpClientHarness(port).url("/api/anything"))
                .exchange((req, resp) -> resp.getStatusCode());

        assertTrue(status.is4xxClientError(),
                "Non-MCP path must require authentication, got " + status);
        assertTrue(status.value() == HttpStatus.UNAUTHORIZED.value()
                        || status.value() == HttpStatus.FORBIDDEN.value()
                        || status.value() == HttpStatus.NOT_FOUND.value(),
                "Expected 401/403 (or 404 if Spring Security short-circuits) but got " + status);
    }

    @Test
    void oversizedRequestBodyIsRejectedBeforeParsing() {
        McpClientHarness harness = new McpClientHarness(port);
        // Comfortably over the 512-byte test limit; the padding is inside a JSON string value so
        // this would otherwise be a syntactically valid (if pointless) JSON-RPC call.
        String oversizedBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"jeap_overview","arguments":{"padding":"%s"}}}
                """.formatted("x".repeat(1000));

        ResponseEntity<String> response = harness.postJsonRpcWithoutStatusCheck(oversizedBody);

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.getStatusCode());
    }

    @Test
    void oversizedChunkedRequestBodyIsRejected() throws Exception {
        // Declares no Content-Length at all - java.net.http.HttpClient sends Transfer-Encoding:
        // chunked whenever the body publisher's length is unknown (BodyPublishers.ofInputStream),
        // which is exactly the bypass McpRequestSizeFilter must also cover, not just an oversized
        // declared Content-Length.
        String oversizedBody = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"jeap_overview","arguments":{"padding":"%s"}}}
                """.formatted("x".repeat(1000));
        byte[] bodyBytes = oversizedBody.getBytes(StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(new McpClientHarness(port).mcpUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bodyBytes)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), response.statusCode());
    }
}
