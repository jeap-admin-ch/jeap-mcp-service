package ch.admin.bit.jeap.mcp;

import org.springframework.ai.util.JacksonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Small test helper that wraps the MCP Streamable-HTTP handshake (initialize → session-id →
 * subsequent calls) for the integration tests. Centralizes the JSON-RPC envelope plumbing so
 * individual ITs only express intent.
 */
public final class McpClientHarness {

    public static final String CONTEXT_PATH = "/jeap-mcp-service";

    private static final JsonMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    private final RestClient restClient = RestClient.create();
    private final String baseUrl;
    private String sessionId;

    public McpClientHarness(int port) {
        this("localhost", port);
    }

    /**
     * Host + port constructor for container-based ITs: Testcontainers' {@code getHost()} is not
     * guaranteed to be {@code localhost} (remote Docker / rootless / CI runners), so the in-JVM
     * {@code int port} constructor's hard-coded {@code localhost} is not always correct there.
     */
    public McpClientHarness(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port + CONTEXT_PATH;
    }

    public String mcpUrl() {
        return baseUrl + "/mcp";
    }

    public String url(String path) {
        return baseUrl + path;
    }

    /**
     * Sends the MCP initialize request and stores the returned session id for subsequent calls.
     */
    public ResponseEntity<String> initialize() {
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-03-26","capabilities":{},
                           "clientInfo":{"name":"test-client","version":"1.0.0"}}}
                """;
        ResponseEntity<String> response = post(mcpUrl(), request, jsonHeaders(null));
        this.sessionId = response.getHeaders().getFirst("mcp-session-id");
        return response;
    }

    public ResponseEntity<String> postJsonRpc(String body) {
        return post(mcpUrl(), body, jsonHeaders(sessionId));
    }

    /**
     * Like {@link #postJsonRpc(String)} but never throws on a 4xx/5xx status: the raw status and
     * body are returned so tests can assert on error responses.
     */
    public ResponseEntity<String> postJsonRpcWithoutStatusCheck(String body) {
        return restClient.post()
                .uri(mcpUrl())
                .headers(h -> h.addAll(jsonHeaders(sessionId)))
                .body(body)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(response.bodyTo(String.class)));
    }

    public ResponseEntity<String> post(String url, String body, HttpHeaders headers) {
        return restClient.post()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private HttpHeaders jsonHeaders(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");
        if (session != null) {
            headers.set("mcp-session-id", session);
        }
        return headers;
    }

    /**
     * Parses an MCP response body into a {@link JsonNode}. Handles both plain JSON responses and
     * Server-Sent Events frames (`event: ...\ndata: {json}\n\n`).
     */
    public static JsonNode parseMcpBody(String body) {
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                return MAPPER.readTree(trimmed);
            }
            for (String line : trimmed.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    return MAPPER.readTree(line.substring("data:".length()).trim());
                }
            }
            throw new IllegalStateException("No JSON-RPC payload found in body: " + body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MCP body: " + body, ex);
        }
    }

    public static List<String> toolNames(JsonNode response) {
        JsonNode tools = response.path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        return names;
    }
}
