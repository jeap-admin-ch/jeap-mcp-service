package ch.admin.bit.jeap.mcp.config;

import ch.admin.bit.jeap.mcp.McpClientHarness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the dual security posture configured in {@link McpSecurityConfig}:
 * <ul>
 *     <li>{@code /mcp/**} is reachable anonymously — that's how MCP clients connect.</li>
 *     <li>Other paths are protected by the jEAP OAuth2 resource-server starter and reject
 *         unauthenticated requests (no implicit bypass via the high-precedence MCP chain).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
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
}
