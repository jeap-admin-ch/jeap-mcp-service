package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPaths;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Invokes a single upstream {@code project-rag} tool over the matching {@link McpSyncClient} and
 * records chunk/parse-failure metrics for the result.
 * <p>
 * Extracted out of {@link JeapRagTools} so the MCP-transport/metrics concern (this class) is
 * separate from tool-argument shaping and validation (which stays in {@link JeapRagTools} and
 * {@link JeapRagProperties}). {@link JeapRagTools} constructs this class itself in its own
 * constructor - there is no separate Spring bean/wiring for it.
 * <p>
 * Retries the upstream call itself (transient transport hiccups - see {@link #buildRetryTemplate}),
 * deliberately as a plain {@link RetryTemplate} rather than a {@code @Retryable}-annotated method:
 * {@link JeapRagTools}' {@code @Tool} methods validate/clamp arguments (recording
 * {@code jeap.mcp.tool.arguments.clamped}/{@code .rejected}) before ever reaching {@link #call}, and
 * an AOP-proxied {@code @Retryable} on a {@code @Tool} method would re-run that argument shaping - and
 * re-record its metrics - on every retry attempt, not just once per external request.
 */
@Slf4j
final class UpstreamRagInvoker {

    private static final String CONNECTION_NAME_SEPARATOR = " - ";
    private static final String PROJECT_RAG_CONNECTION = "project-rag";
    private static final String FILE_PATH = "file_path";
    private static final JsonMapper JSON_MAPPER = JacksonUtils.getDefaultJsonMapper();

    private final McpSyncClient projectRagClient;
    private final McpMetrics metrics;
    private final RetryTemplate retryTemplate;

    /**
     * @param mcpSyncClients     all connected MCP clients; the one whose client-info name matches
     *                           {@code <mcpClientName> - project-rag} is selected.
     * @param mcpClientEnabled   if {@code true} and no matching client is found, the constructor fails
     *                           fast; otherwise it only logs a warning and {@link #call} throws lazily
     *                           at first use.
     * @param retryMaxAttempts   maximum number of attempts (including the first) for {@link #call}
     * @param retryBackoffMillis fixed delay between retry attempts, in milliseconds
     */
    UpstreamRagInvoker(List<McpSyncClient> mcpSyncClients, String mcpClientName, boolean mcpClientEnabled,
                       McpMetrics metrics, int retryMaxAttempts, long retryBackoffMillis) {
        this.metrics = metrics;
        this.retryTemplate = buildRetryTemplate(retryMaxAttempts, retryBackoffMillis);
        String expectedClientInfoName = mcpClientName + CONNECTION_NAME_SEPARATOR + PROJECT_RAG_CONNECTION;
        this.projectRagClient = mcpSyncClients.stream()
                .filter(client -> expectedClientInfoName.equals(client.getClientInfo().name()))
                .findFirst()
                .orElse(null);
        if (this.projectRagClient == null) {
            if (mcpClientEnabled) {
                throw new IllegalStateException(
                        "spring.ai.mcp.client.enabled=true but no McpSyncClient with client-info name '"
                                + expectedClientInfoName + "' is connected. "
                                + "Ensure spring.ai.mcp.client.stdio.connections." + PROJECT_RAG_CONNECTION
                                + " is configured and reachable.");
            }
            log.warn("No '{}' MCP client connection found - jEAP RAG tools will fail at call time. "
                            + "Set spring.ai.mcp.client.enabled=true and configure "
                            + "spring.ai.mcp.client.stdio.connections.{} to enable them.",
                    PROJECT_RAG_CONNECTION, PROJECT_RAG_CONNECTION);
        }
    }

    /**
     * IllegalStateException (e.g. no project-rag client connected) is excluded from retry because it
     * signals a permanent configuration problem, not a transient transport hiccup - the SDK's
     * StdioClientTransport.sendMessage throws a plain RuntimeException("Failed to enqueue message")
     * when its outbound Sinks.Many cannot enqueue the request right now (back-pressure / emit
     * interleaving), and that is what this retry recovers from.
     */
    private static RetryTemplate buildRetryTemplate(int maxAttempts, long backoffMillis) {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(Exception.class, true);
        retryableExceptions.put(IllegalStateException.class, false);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, retryableExceptions);

        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(backoffMillis);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

    String call(String jeapToolName, String upstreamToolName, Map<String, Object> arguments) {
        return retryTemplate.execute(context -> doCall(jeapToolName, upstreamToolName, arguments));
    }

    private String doCall(String jeapToolName, String upstreamToolName, Map<String, Object> arguments) {
        if (projectRagClient == null) {
            throw new IllegalStateException(
                    "Cannot invoke upstream tool '" + upstreamToolName + "': no 'project-rag' MCP client is connected.");
        }
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(upstreamToolName)
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = projectRagClient.callTool(request);
        log.debug("Upstream tool '{}' returned {} content item(s); arg keys: {}; isError={}",
                upstreamToolName,
                result.content() == null ? 0 : result.content().size(),
                arguments.keySet(),
                result.isError());
        String serialized = serializeContent(result.content());
        // isError is optional per the MCP spec (absent ⇒ success), so treat null as success and
        // only skip the metric when it is explicitly true — upstream errors are non-JSON by design.
        if (!Boolean.TRUE.equals(result.isError())) {
            recordUpstreamChunks(jeapToolName, serialized);   // counts results[] chunks; never throws
        }
        return serialized;
    }

    /**
     * Count each {@code results[]} chunk of a successful upstream response, tagged by file extension,
     * area (docs|code) and the jEAP tool. Tools whose response carries no {@code results[]} array
     * (find_*, get_call_graph, get_statistics) no-op; non-JSON successful text (no leading
     * <code>{</code>/<code>[</code>) is also a no-op, not a parse failure — both surface as an empty
     * {@link UpstreamResults#chunks(String)} result.
     * <p>
     * {@link DocPaths} classifies the path. <b>Never throws:</b> a body that looks like JSON but does
     * not parse increments the parse-failure counter instead.
     */
    private void recordUpstreamChunks(String jeapToolName, String serialized) {
        try {
            for (JsonNode chunk : UpstreamResults.chunks(serialized).orElse(List.of())) {
                String filePath = chunk.path(FILE_PATH).asString("");
                metrics.recordUpstreamChunk(jeapToolName, DocPaths.extension(filePath), DocPaths.area(filePath));
            }
        } catch (Exception e) {   // metrics must never fail a tool call
            metrics.recordParseFailure(jeapToolName);
            log.debug("upstream-chunk metric parse failed for {}: {}", jeapToolName, e.toString());
        }
    }

    private static String serializeContent(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (content.stream().allMatch(McpSchema.TextContent.class::isInstance)) {
            return content.stream()
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .collect(Collectors.joining());
        }
        return JSON_MAPPER.writeValueAsString(content);
    }
}
