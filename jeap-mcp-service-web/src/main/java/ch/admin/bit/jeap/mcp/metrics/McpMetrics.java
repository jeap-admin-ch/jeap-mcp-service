package ch.admin.bit.jeap.mcp.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Central recorder for the MCP server's custom metrics. Owns the {@link MeterRegistry} so the tool
 * classes don't have to know how meters are named, tagged, or registered — they inject this bean and
 * call its methods.
 * <p>
 * Two mechanisms live here, by necessity:
 * <ul>
 *   <li>The {@code @Timed} timer ({@link #TIMER_NAME}) is <b>declarative</b>: its name and tags must
 *       be compile-time constants, so they stay as {@code static final} fields referenced directly
 *       from the {@code @Timed} annotations on each {@code @Tool} method. There is no instance method
 *       for it here — Micrometer's {@code TimedAspect} records it around the annotated call.</li>
 *   <li>The <b>programmatic</b> counters (upstream chunks, parse failures, doc fetches) are recorded
 *       through the instance methods below.</li>
 * </ul>
 */
public class McpMetrics {

    public static final String TIMER_NAME = "jeap.mcp.tool.calls";
    public static final String DESCRIPTION = "Duration of MCP tool calls. The timer count is the MCP tool usage count.";
    public static final String TOOL_TAG = "tool";

    // Usage metric over upstream RAG result chunks. One increment per results[] chunk of a
    // successful, non-error upstream response, tagged by file extension + area (docs|code) + jEAP tool.
    public static final String UPSTREAM_CHUNKS_FETCHED_COUNTER = "jeap.mcp.upstream.chunks.fetched";
    static final String UPSTREAM_CHUNKS_FETCHED_DESCRIPTION =
            "Upstream RAG result chunks returned, by file extension, area (docs|code) and jEAP tool.";
    public static final String EXTENSION_TAG = "extension";
    public static final String AREA_TAG = "area";

    // Counter for when a successful (non-error) upstream response cannot be parsed as expected.
    public static final String PARSE_FAILURES_COUNTER = "jeap.mcp.upstream.parse.failures";
    static final String PARSE_FAILURES_DESCRIPTION =
            "Failures to parse a successful upstream response for the upstream chunks metric.";

    // Separate counter for deliberate document fetches via jeap_get_document. Kept distinct from
    // the upstream chunks metric because a document fetch is NOT a RAG result chunk.
    public static final String DOCS_FETCHED_COUNTER = "jeap.mcp.docs.fetched";
    static final String DOCS_FETCHED_DESCRIPTION =
            "Documents fetched via jeap_get_document, by file extension and area (docs|code).";

    // Counter for MCP Resource reads (resources/read), distinct from the tool-based
    // jeap.mcp.docs.fetched counter above: same underlying docs corpus, different MCP feature
    // (Resource vs Tool), so usage can be attributed and analyzed per surface.
    public static final String RESOURCE_READS_COUNTER = "jeap.mcp.resource.reads";
    static final String RESOURCE_READS_DESCRIPTION =
            "Documentation MCP resources read via resources/read, by resource name, file extension and "
                    + "area (docs|code).";
    public static final String RESOURCE_TAG = "resource";

    // Counter for completion/complete requests against a RESOURCE template's argument (e.g. the
    // jeap-docs://{ref} ref), distinct from resource reads: a completion request is intent/discovery,
    // not a read of document content. "resource" is in the metric name (not just a tag) because MCP
    // completion also covers prompt arguments (ref/prompt); naming it explicitly leaves room for a
    // parallel jeap.mcp.prompt.completion.calls without a clash, should this server ever add prompts.
    // Tagged by the same RESOURCE_TAG as recordResourceRead so the two can be compared directly (intent
    // vs. actual read) with a single "by (resource)" PromQL grouping.
    public static final String RESOURCE_COMPLETION_CALLS_COUNTER = "jeap.mcp.resource.completion.calls";
    static final String RESOURCE_COMPLETION_CALLS_DESCRIPTION =
            "Completion requests (completion/complete) for a resource template's argument, by resource "
                    + "name and whether any suggestion matched.";
    public static final String MATCHED_TAG = "matched";

    private final MeterRegistry meterRegistry;

    public McpMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Count a single upstream {@code results[]} chunk, tagged by {@code extension}, {@code area}
     * (docs|code) and the originating jEAP tool name.
     */
    public void recordUpstreamChunk(String jeapToolName, String extension, String area) {
        Counter.builder(UPSTREAM_CHUNKS_FETCHED_COUNTER)
                .description(UPSTREAM_CHUNKS_FETCHED_DESCRIPTION)
                .tag(EXTENSION_TAG, extension)
                .tag(AREA_TAG, area)
                .tag(TOOL_TAG, jeapToolName)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Count one failure to parse an otherwise-successful upstream response. The caller increments this
     * instead of {@link #recordUpstreamChunk} when the response cannot be read.
     */
    public void recordParseFailure(String jeapToolName) {
        Counter.builder(PARSE_FAILURES_COUNTER)
                .description(PARSE_FAILURES_DESCRIPTION)
                .tag(TOOL_TAG, jeapToolName)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Count a deliberate document fetch via {@code jeap_get_document}, tagged by {@code extension}
     * and {@code area} (docs|code).
     */
    public void recordDocFetched(String extension, String area) {
        Counter.builder(DOCS_FETCHED_COUNTER)
                .description(DOCS_FETCHED_DESCRIPTION)
                .tag(EXTENSION_TAG, extension)
                .tag(AREA_TAG, area)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Count one MCP resource read ({@code resources/read}), tagged by {@code resourceName} (the
     * {@code @McpResource} {@code name}, e.g. {@code jeap_doc} or {@code jeap_docs_index}),
     * {@code extension} and {@code area} (docs|code).
     */
    public void recordResourceRead(String resourceName, String extension, String area) {
        Counter.builder(RESOURCE_READS_COUNTER)
                .description(RESOURCE_READS_DESCRIPTION)
                .tag(RESOURCE_TAG, resourceName)
                .tag(EXTENSION_TAG, extension)
                .tag(AREA_TAG, area)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Count one completion request ({@code completion/complete}) for a resource template's argument,
     * tagged by {@code resource} (the {@code @McpResource} {@code name} the template belongs to, e.g.
     * {@code jeap_doc}) and {@code matched} (whether at least one suggestion was returned). The raw,
     * user-typed value is deliberately NOT a tag — it is unbounded, free-text input and would blow up
     * cardinality; log it instead if per-query visibility is ever needed.
     */
    public void recordResourceCompletionCall(String resourceName, boolean matched) {
        Counter.builder(RESOURCE_COMPLETION_CALLS_COUNTER)
                .description(RESOURCE_COMPLETION_CALLS_DESCRIPTION)
                .tag(RESOURCE_TAG, resourceName)
                .tag(MATCHED_TAG, String.valueOf(matched))
                .register(meterRegistry)
                .increment();
    }
}
