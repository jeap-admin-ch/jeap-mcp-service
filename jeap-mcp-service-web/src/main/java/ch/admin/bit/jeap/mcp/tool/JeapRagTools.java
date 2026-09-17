package ch.admin.bit.jeap.mcp.tool;

import ch.admin.bit.jeap.mcp.metrics.McpMetrics;
import io.micrometer.core.annotation.Timed;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jEAP-branded RAG tools that internally invoke the upstream {@code project-rag} MCP server.
 * <p>
 * Each {@code @Tool} method declares its own fixed input schema, validates its arguments, and
 * delegates the actual upstream call to {@link UpstreamRagInvoker}. This replaces the previous
 * auto-proxy/filter/rename machinery so the exposed surface is defined in one Java file.
 * <p>
 * This class only shapes and validates arguments; it deliberately does not talk to the
 * {@link McpSyncClient} itself - see {@link UpstreamRagInvoker} for that (kept as a separate
 * collaborator so this file stays focused on the tool surface) and {@link JeapRagProperties} for
 * the validation/clamping rules themselves. It does hold a {@link McpMetrics} reference, passed
 * through to {@link JeapRagProperties#validate(String, McpMetrics, Runnable)} so a rejected
 * argument can be counted - see that method's javadoc.
 * <p>
 * <b>Invariant:</b> do not enable {@code spring.ai.mcp.client.toolcallback.enabled}. Spring AI's
 * {@code SyncMcpToolCallbackProvider} would re-publish every upstream {@code project-rag} tool
 * (including write tools like {@code index_codebase} / {@code clear_index}) alongside the
 * {@code jeap_*} wrappers defined here. The wrappers in this class are the only sanctioned surface.
 * <p>
 * <b>Invariant:</b> {@code limit}/{@code depth} arguments are clamped server-side via
 * {@link JeapRagProperties} before being forwarded upstream - see its javadoc for why: the
 * {@code jeap_*} tools are reachable anonymously, so an unbounded client-supplied value would be a
 * cheap way to force an expensive query against the shared {@code project-rag} sidecar.
 * <p>
 * <b>Invariant:</b> the {@code file_path} argument of the location-based tools ({@code
 * findDefinition}, {@code findReferences}, {@code getCallGraph}) is checked by
 * {@link RagFilePathPolicy} before being forwarded upstream - see its javadoc for why: {@code
 * project-rag} canonicalizes and reads whatever path it is given with no containment check of its
 * own, so an unchecked {@code file_path} would be an arbitrary-file-read primitive.
 * <p>
 * The upstream client is selected by matching its MCP client-info name against
 * {@code <spring.ai.mcp.client.name> - project-rag}. If the MCP client is enabled but no matching
 * client is connected at startup, the bean fails fast (see {@link UpstreamRagInvoker}).
 */
@SuppressWarnings("UnusedReturnValue")
@Component
public class JeapRagTools {

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
    // — UpstreamRagInvoker retries the upstream call transparently; see its javadoc for why that
    // retry lives there (a RetryTemplate) rather than as @Retryable on the @Tool methods below.

    private final UpstreamRagInvoker upstreamInvoker;
    private final DocumentSearch documentSearch;
    private final JeapRagProperties ragProperties;
    private final RagFilePathPolicy ragFilePathPolicy;
    private final McpMetrics metrics;

    /**
     * @param mcpSyncClients     all connected MCP clients; the one whose client-info name matches
     *                           {@code <mcpClientName> - project-rag} backs the upstream calls below.
     * @param mcpClientName      this service's own MCP client name (see {@code spring.ai.mcp.client.name})
     * @param mcpClientEnabled   if {@code true} and no matching client is connected, construction fails fast
     * @param retryMaxAttempts   maximum attempts (including the first) for a retried upstream call
     * @param retryBackoffMillis fixed delay between retry attempts, in milliseconds
     * @param metrics            upstream-call metrics, recorded by the constructed {@link UpstreamRagInvoker}
     * @param documentSearch     builds/assembles {@code jeap_find_in_documentation}'s request/response
     * @param ragProperties      validation/clamping policy for all tool arguments below
     * @param ragFilePathPolicy  containment policy for the location-based tools' {@code file_path} argument
     */
    public JeapRagTools(List<McpSyncClient> mcpSyncClients,
                        @Value("${spring.ai.mcp.client.name:spring-ai-mcp-client}") String mcpClientName,
                        @Value("${spring.ai.mcp.client.enabled:false}") boolean mcpClientEnabled,
                        @Value("${jeap.mcp.upstream.retry.max-attempts:3}") int retryMaxAttempts,
                        @Value("${jeap.mcp.upstream.retry.backoff-millis:500}") long retryBackoffMillis,
                        McpMetrics metrics,
                        DocumentSearch documentSearch,
                        JeapRagProperties ragProperties,
                        RagFilePathPolicy ragFilePathPolicy) {
        this.documentSearch = documentSearch;
        this.ragProperties = ragProperties;
        this.ragFilePathPolicy = ragFilePathPolicy;
        this.metrics = metrics;
        this.upstreamInvoker = new UpstreamRagInvoker(mcpSyncClients, mcpClientName, mcpClientEnabled, metrics,
                retryMaxAttempts, retryBackoffMillis);
    }

    /**
     * {@code jeap_find_code_examples} - see the {@link Tool} annotation's description.
     *
     * @param query    natural-language query about jEAP source or examples
     * @param path     optional repository path filter
     * @param project  optional jeap-* project name filter
     * @param limit    optional, server-capped maximum result count
     * @param minScore optional minimum similarity score
     * @param hybrid   optional hybrid (vector + keyword) search flag
     * @return the upstream tool's serialized result, or a validation error message
     */
    @Tool(name = TOOL_FIND_CODE_EXAMPLES,
            description = "Find authoritative examples, implementation patterns, and usage snippets from " +
                    "the indexed jEAP repositories. Use this before GitHub/web search for any question " +
                    "about jEAP APIs, annotations, listeners, inbox/outbox, messaging, Kafka, Spring Boot " +
                    "integration, or migration examples.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_CODE_EXAMPLES})
    public String findCodeExamples(
            @ToolParam(description = "Natural-language query about jEAP source or examples.")
            String query,
            @ToolParam(required = false, description = "Restrict search to a specific repository path inside the indexed corpus.")
            String path,
            @ToolParam(required = false, description = "Restrict search to a specific jeap-* project name.")
            String project,
            @ToolParam(required = false, description = "Maximum number of results to return (default 10, server-capped).")
            Integer limit,
            @ToolParam(required = false, description = "Minimum similarity score for results (default 0.7).")
            Double minScore,
            @ToolParam(required = false, description = "Use hybrid (vector + keyword) search (default true).")
            Boolean hybrid) {
        return ragProperties.validate(TOOL_FIND_CODE_EXAMPLES, metrics, () -> {
            ragProperties.requireWithinLength(QUERY, query);
            ragProperties.requireWithinLength(PATH, path);
            ragProperties.requireWithinLength(PROJECT, project);
        }).orElseGet(() -> upstreamInvoker.call(TOOL_FIND_CODE_EXAMPLES, QUERY_CODEBASE, new Args()
                .put(QUERY, query)
                .put(PATH, path)
                .put(PROJECT, project)
                .put(LIMIT, ragProperties.clampLimit(limit, TOOL_FIND_CODE_EXAMPLES, metrics))
                .put(MIN_SCORE, minScore)
                .put(HYBRID, hybrid)
                .build()));
    }

    /**
     * {@code jeap_find_definition} - see the {@link Tool} annotation's description.
     *
     * @param filePath path of the file containing the symbol
     * @param line     1-based line number of the symbol
     * @param column   0-based column index of the symbol on the given line
     * @param project  optional jeap-* project name filter
     * @return the upstream tool's serialized result, or a validation error message
     */
    @Tool(name = TOOL_FIND_DEFINITION,
            description = "Locate the definition of a class, function, or symbol inside the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_DEFINITION})
    public String findDefinition(
            @ToolParam(description = "Absolute path (under the indexed source root) of the file containing the symbol.")
            String filePath,
            @ToolParam(description = "1-based line number of the symbol.")
            int line,
            @ToolParam(description = "0-based column index of the symbol on the given line.")
            int column,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return ragProperties.validate(TOOL_FIND_DEFINITION, metrics, () -> {
            ragProperties.requireWithinLength(FILE_PATH, filePath);
            ragProperties.requireWithinLength(PROJECT, project);
            ragFilePathPolicy.requireContained(FILE_PATH, filePath);
        }).orElseGet(() -> upstreamInvoker.call(TOOL_FIND_DEFINITION, FIND_DEFINITION, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(PROJECT, project)
                .build()));
    }

    /**
     * {@code jeap_find_references} - see the {@link Tool} annotation's description.
     *
     * @param filePath path of the file containing the symbol
     * @param line     1-based line number of the symbol
     * @param column   0-based column index of the symbol on the given line
     * @param limit    optional, server-capped maximum result count
     * @param project  optional jeap-* project name filter
     * @return the upstream tool's serialized result, or a validation error message
     */
    @Tool(name = TOOL_FIND_REFERENCES,
            description = "Find usages of a symbol across the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_FIND_REFERENCES})
    public String findReferences(
            @ToolParam(description = "Absolute path (under the indexed source root) of the file containing the symbol.")
            String filePath,
            @ToolParam(description = "1-based line number of the symbol.")
            int line,
            @ToolParam(description = "0-based column index of the symbol on the given line.")
            int column,
            @ToolParam(required = false, description = "Maximum number of references to return (server-capped).")
            Integer limit,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return ragProperties.validate(TOOL_FIND_REFERENCES, metrics, () -> {
            ragProperties.requireWithinLength(FILE_PATH, filePath);
            ragProperties.requireWithinLength(PROJECT, project);
            ragFilePathPolicy.requireContained(FILE_PATH, filePath);
        }).orElseGet(() -> upstreamInvoker.call(TOOL_FIND_REFERENCES, FIND_REFERENCES, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(LIMIT, ragProperties.clampLimit(limit, TOOL_FIND_REFERENCES, metrics))
                .put(PROJECT, project)
                .build()));
    }

    /**
     * {@code jeap_get_call_graph} - see the {@link Tool} annotation's description.
     *
     * @param filePath path of the file containing the function
     * @param line     1-based line number of the function
     * @param column   0-based column index of the function on the given line
     * @param depth    optional, server-capped call-graph depth
     * @param project  optional jeap-* project name filter
     * @return the upstream tool's serialized result, or a validation error message
     */
    @Tool(name = TOOL_GET_CALL_GRAPH,
            description = "Return the call graph for a function inside the indexed jEAP codebase.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_GET_CALL_GRAPH})
    public String getCallGraph(
            @ToolParam(description = "Absolute path (under the indexed source root) of the file containing the function.")
            String filePath,
            @ToolParam(description = "1-based line number of the function.")
            int line,
            @ToolParam(description = "0-based column index of the function on the given line.")
            int column,
            @ToolParam(required = false, description = "Call-graph depth (default 2, server-capped).")
            Integer depth,
            @ToolParam(required = false, description = "Restrict lookup to a specific jeap-* project name.")
            String project) {
        return ragProperties.validate(TOOL_GET_CALL_GRAPH, metrics, () -> {
            ragProperties.requireWithinLength(FILE_PATH, filePath);
            ragProperties.requireWithinLength(PROJECT, project);
            ragFilePathPolicy.requireContained(FILE_PATH, filePath);
        }).orElseGet(() -> upstreamInvoker.call(TOOL_GET_CALL_GRAPH, GET_CALL_GRAPH, new Args()
                .put(FILE_PATH, filePath)
                .put(LINE, line)
                .put(COLUMN, column)
                .put(DEPTH, ragProperties.clampDepth(depth, TOOL_GET_CALL_GRAPH, metrics))
                .put(PROJECT, project)
                .build()));
    }

    /**
     * {@code jeap_search_by_filters} - see the {@link Tool} annotation's description.
     *
     * @param query          natural-language query
     * @param fileExtensions optional file-extension filter
     * @param languages      optional programming-language filter
     * @param pathPatterns   optional glob-like path pattern filter
     * @param limit          optional, server-capped maximum result count
     * @param project        optional jeap-* project name filter
     * @return the upstream tool's serialized result, or a validation error message
     */
    @Tool(name = TOOL_SEARCH_BY_FILTERS,
            description = "Filtered (path / type / language) search over the indexed jEAP codebase. " +
                    "Use when the user constrains the search to a specific jeap-* repository or file type.")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_SEARCH_BY_FILTERS})
    public String searchByFilters(
            @ToolParam(description = "Natural-language query.")
            String query,
            @ToolParam(required = false, description = "Filter by file extensions, e.g. [\"java\", \"yml\"].")
            List<String> fileExtensions,
            @ToolParam(required = false, description = "Filter by programming language names, e.g. [\"java\", \"kotlin\"].")
            List<String> languages,
            @ToolParam(required = false, description = "Glob-like path patterns to match.")
            List<String> pathPatterns,
            @ToolParam(required = false, description = "Maximum number of results (server-capped).")
            Integer limit,
            @ToolParam(required = false, description = "Restrict search to a specific jeap-* project name.")
            String project) {
        return ragProperties.validate(TOOL_SEARCH_BY_FILTERS, metrics, () -> {
            ragProperties.requireWithinLength(QUERY, query);
            ragProperties.requireWithinLength(PROJECT, project);
            ragProperties.requireWithinSize(FILE_EXTENSIONS, fileExtensions);
            ragProperties.requireWithinSize(LANGUAGES, languages);
            ragProperties.requireWithinSize(PATH_PATTERNS, pathPatterns);
        }).orElseGet(() -> upstreamInvoker.call(TOOL_SEARCH_BY_FILTERS, SEARCH_BY_FILTERS, new Args()
                .put(QUERY, query)
                .put(FILE_EXTENSIONS, fileExtensions)
                .put(LANGUAGES, languages)
                .put(PATH_PATTERNS, pathPatterns)
                .put(LIMIT, ragProperties.clampLimit(limit, TOOL_SEARCH_BY_FILTERS, metrics))
                .put(PROJECT, project)
                .build()));
    }

    /**
     * {@code jeap_get_statistics} - see the {@link Tool} annotation's description.
     *
     * @return the upstream tool's serialized result
     */
    @Tool(name = TOOL_GET_STATISTICS,
            description = "Return index statistics for the bundled jEAP RAG corpus (sanity / debugging).")
    @Timed(value = McpMetrics.TIMER_NAME,
            description = McpMetrics.DESCRIPTION,
            extraTags = {McpMetrics.TOOL_TAG, TOOL_GET_STATISTICS})
    public String getStatistics() {
        return upstreamInvoker.call(TOOL_GET_STATISTICS, GET_STATISTICS, Map.of());
    }

    /**
     * {@code jeap_find_in_documentation} - see the {@link Tool} annotation's description.
     *
     * @param query natural-language documentation question about jEAP
     * @param limit optional maximum number of documents (up to 5)
     * @return the assembled document(s), or a validation error message
     */
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
    public String findInDocumentation(
            @ToolParam(description = "Natural-language documentation question about jEAP.")
            String query,
            @ToolParam(required = false, description = "Maximum number of documents (up to 5).")
            Integer limit) {
        return ragProperties.validate(TOOL_FIND_IN_DOCUMENTATION, metrics, () -> ragProperties.requireWithinLength(QUERY, query))
                .orElseGet(() -> {
                    String upstreamJson = upstreamInvoker.call(TOOL_FIND_IN_DOCUMENTATION, QUERY_CODEBASE,
                            documentSearch.requestArgs(query, limit));
                    return documentSearch.assembleDocuments(upstreamJson, limit);
                });
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
