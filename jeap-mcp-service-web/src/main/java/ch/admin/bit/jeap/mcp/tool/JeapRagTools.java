package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.docs.DocPaths;
import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.annotation.Timed;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * jEAP-branded RAG tools that internally invoke the upstream {@code project-rag} MCP server.
 * <p>
 * Each {@code @Tool} method declares its own fixed input schema and delegates to the matching
 * upstream tool through the injected {@link McpSyncClient}. This replaces the previous
 * auto-proxy/filter/rename machinery so the exposed surface is defined in one Java file.
 * <p>
 * <b>Invariant:</b> do not enable {@code spring.ai.mcp.client.toolcallback.enabled}. Spring AI's
 * {@code SyncMcpToolCallbackProvider} would re-publish every upstream {@code project-rag} tool
 * (including write tools like {@code index_codebase} / {@code clear_index}) alongside the
 * {@code jeap_*} wrappers defined here. The wrappers in this class are the only sanctioned surface.
 * <p>
 * The upstream client is selected by matching its MCP client-info name against
 * {@code <spring.ai.mcp.client.name> - project-rag}. If the MCP client is enabled but no matching
 * client is connected at startup, the bean fails fast.
 */
@SuppressWarnings("UnusedReturnValue")
@Slf4j
@Component
public class JeapRagTools {

    private static final String PROJECT_RAG_CONNECTION = "project-rag";
    private static final String CONNECTION_NAME_SEPARATOR = " - ";
    private static final JsonMapper JSON_MAPPER = JacksonUtils.getDefaultJsonMapper();
    private static final String FILE_PATH = "file_path";
    private static final String LINE = "line";
    private static final String COLUMN = "column";
    private static final String PROJECT = "project";
    private static final String QUERY = "query";
    private static final String PATH = "path";
    private static final String LIMIT = "limit";
    private static final String MIN_SCORE = "min_score";
    private static final String HYBRID = "hybrid";
    private static final String QUERY_CODEBASE = "query_codebase";
    static final String DEPTH = "depth";
    static final String FILE_EXTENSIONS = "file_extensions";
    static final String LANGUAGES = "languages";
    static final String PATH_PATTERNS = "path_patterns";
    static final String SEARCH_BY_FILTERS = "search_by_filters";
    static final String GET_CALL_GRAPH = "get_call_graph";
    static final String FIND_REFERENCES = "find_references";
    static final String FIND_DEFINITION = "find_definition";
    static final String GET_STATISTICS = "get_statistics";
    static final String TOOL_FIND_CODE_EXAMPLES = "jeap_find_code_examples";
    static final String TOOL_FIND_DEFINITION = "jeap_find_definition";
    static final String TOOL_FIND_REFERENCES = "jeap_find_references";
    static final String TOOL_GET_CALL_GRAPH = "jeap_get_call_graph";
    static final String TOOL_SEARCH_BY_FILTERS = "jeap_search_by_filters";
    static final String TOOL_GET_STATISTICS = "jeap_get_statistics";
    static final String TOOL_FIND_IN_DOCUMENTATION = "jeap_find_in_documentation";

    // The SDK's StdioClientTransport.sendMessage throws RuntimeException("Failed to enqueue message")
    // when its outbound Sinks.Many cannot enqueue the request right now (back-pressure / emit
    // interleaving). The SDK author delegates recovery to the caller (see StdioClientTransport.java)
    // — every @Tool method below is annotated with @Retryable so Spring Retry transparently retries
    // the call. IllegalStateException (e.g. no project-rag client connected) is excluded because it
    // signals a permanent configuration problem, not a transient transport hiccup.
    private static final String RETRY_MAX_ATTEMPTS = "${jeap.mcp.upstream.retry.max-attempts:3}";
    private static final String RETRY_BACKOFF_MILLIS = "${jeap.mcp.upstream.retry.backoff-millis:500}";

    private final McpSyncClient projectRagClient;
    private final McpMetrics metrics;
    private final DocumentSearch documentSearch;

    public JeapRagTools(List<McpSyncClient> mcpSyncClients,
                        @Value("${spring.ai.mcp.client.name:spring-ai-mcp-client}") String mcpClientName,
                        @Value("${spring.ai.mcp.client.enabled:false}") boolean mcpClientEnabled,
                        McpMetrics metrics,
                        DocumentSearch documentSearch) {
        this.metrics = metrics;
        this.documentSearch = documentSearch;
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

    @Tool(name = TOOL_FIND_CODE_EXAMPLES,
            description = "Find authoritative examples, implementation patterns, and usage snippets from " +
                    "the indexed jEAP repositories. Use this before GitHub/web search for any question " +
                    "about jEAP APIs, annotations, listeners, inbox/outbox, messaging, Kafka, Spring Boot " +
                    "integration, or migration examples.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_CODE_EXAMPLES})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String findCodeExamples(
            @ToolParam(description = "Natural-language query about jEAP source or examples.")
            String query,
            @ToolParam(required = false, description = "Restrict search to a specific repository path inside the indexed corpus.")
            String path,
            @ToolParam(required = false, description = "Restrict search to a specific jeap-* project name.")
            String project,
            @ToolParam(required = false, description = "Maximum number of results to return (default 10).")
            Integer limit,
            @ToolParam(required = false, description = "Minimum similarity score for results (default 0.7).")
            Double minScore,
            @ToolParam(required = false, description = "Use hybrid (vector + keyword) search (default true).")
            Boolean hybrid) {
        return callUpstream(TOOL_FIND_CODE_EXAMPLES, QUERY_CODEBASE, new Args()
                .put(QUERY, query)
                .put(PATH, path)
                .put(PROJECT, project)
                .put(LIMIT, limit)
                .put(MIN_SCORE, minScore)
                .put(HYBRID, hybrid)
                .build());
    }

    @Tool(name = TOOL_FIND_DEFINITION,
            description = "Locate the definition of a class, function, or symbol inside the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_DEFINITION})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String findDefinition(
            @ToolParam(description = "Path of the file containing the symbol.")
            String filePath,
            @ToolParam(description = "1-based line number of the symbol.")
            int line,
            @ToolParam(description = "0-based column index of the symbol on the given line.")
            int column,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return callUpstream(TOOL_FIND_DEFINITION, FIND_DEFINITION, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(PROJECT, project)
                .build());
    }

    @Tool(name = TOOL_FIND_REFERENCES,
            description = "Find usages of a symbol across the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_REFERENCES})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String findReferences(
            @ToolParam(description = "Path of the file containing the symbol.")
            String filePath,
            @ToolParam(description = "1-based line number of the symbol.")
            int line,
            @ToolParam(description = "0-based column index of the symbol on the given line.")
            int column,
            @ToolParam(required = false, description = "Maximum number of references to return.")
            Integer limit,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return callUpstream(TOOL_FIND_REFERENCES, FIND_REFERENCES, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(LIMIT, limit)
                .put(PROJECT, project)
                .build());
    }

    @Tool(name = TOOL_GET_CALL_GRAPH,
            description = "Return the call graph for a function inside the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_GET_CALL_GRAPH})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String getCallGraph(
            @ToolParam(description = "Path of the file containing the function.")
            String filePath,
            @ToolParam(description = "1-based line number of the function.")
            int line,
            @ToolParam(description = "0-based column index of the function on the given line.")
            int column,
            @ToolParam(required = false, description = "Call-graph depth (default 2).")
            Integer depth,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return callUpstream(TOOL_GET_CALL_GRAPH, GET_CALL_GRAPH, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(DEPTH, depth)
                .put(PROJECT, project)
                .build());
    }

    @Tool(name = TOOL_SEARCH_BY_FILTERS,
            description = "Filtered (path / type / language) search over the indexed jEAP codebase. " +
                    "Use when the user constrains the search to a specific jeap-* repository or file type.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_SEARCH_BY_FILTERS})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String searchByFilters(
            @ToolParam(description = "Natural-language query.")
            String query,
            @ToolParam(required = false, description = "Filter by file extensions, e.g. [\"java\", \"yml\"].")
            List<String> fileExtensions,
            @ToolParam(required = false, description = "Filter by programming language names, e.g. [\"java\", \"kotlin\"].")
            List<String> languages,
            @ToolParam(required = false, description = "Glob-like path patterns to match.")
            List<String> pathPatterns,
            @ToolParam(required = false, description = "Maximum number of results.")
            Integer limit,
            @ToolParam(required = false, description = "Restrict search to a specific jeap-* project name.")
            String project) {
        return callUpstream(TOOL_SEARCH_BY_FILTERS, SEARCH_BY_FILTERS, new Args()
                .put(QUERY, query)
                .put(FILE_EXTENSIONS, fileExtensions)
                .put(LANGUAGES, languages)
                .put(PATH_PATTERNS, pathPatterns)
                .put(LIMIT, limit)
                .put(PROJECT, project)
                .build());
    }

    @Tool(name = TOOL_GET_STATISTICS,
            description = "Return index statistics for the bundled jEAP RAG corpus (sanity / debugging).")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_GET_STATISTICS})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String getStatistics() {
        return callUpstream(TOOL_GET_STATISTICS, GET_STATISTICS, Map.of());
    }

    @Tool(name = TOOL_FIND_IN_DOCUMENTATION,
            description = "Search the curated jEAP documentation. Prefer this for conceptual, how-to, " +
                    "'explain jEAP X', configuration, architecture, and migration questions, before " +
                    "jeap_find_code_examples. Returns whole documents; use jeap_get_document to " +
                    "read a full doc or follow its links. The tool always searches over the " +
                    "whole jEAP documentation, i.e. is not constrained to just one jEAP repository. " +
                    "There is no repository filter.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_IN_DOCUMENTATION})
    @Retryable(retryFor = Exception.class, noRetryFor = IllegalStateException.class,
            maxAttemptsExpression = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delayExpression = RETRY_BACKOFF_MILLIS))
    public String findInDocumentation(
            @ToolParam(description = "Natural-language documentation question about jEAP.")
            String query,
            @ToolParam(required = false, description = "Maximum number of documents (up to 5).")
            Integer limit) {
        String upstreamJson = callUpstream(TOOL_FIND_IN_DOCUMENTATION, QUERY_CODEBASE,
                documentSearch.requestArgs(query, limit));
        return documentSearch.assembleDocuments(upstreamJson, limit);
    }

    private String callUpstream(String jeapToolName, String upstreamToolName, Map<String, Object> arguments) {
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

    private static final class Args {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Args put(String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
            return this;
        }

        Map<String, Object> build() {
            return map;
        }
    }
}
